package dev.vesta.app

import dev.vesta.agents.ChatAgent
import dev.vesta.agents.CodeAgent
import dev.vesta.agents.TaskAgent
import dev.vesta.agents.AppGroupSelector
import dev.vesta.agents.Prompts
import dev.vesta.mcp.McpToolSource
import dev.vesta.conversation.InMemoryApprovalBroker
import dev.vesta.core.config.WebMode
import dev.vesta.storage.ExposedToolRunLog
import dev.vesta.tools.CurrentTimeTool
import dev.vesta.tools.ListFilesTool
import dev.vesta.tools.MakeDirectoryTool
import dev.vesta.tools.MoveFileTool
import dev.vesta.tools.PathSandbox
import dev.vesta.tools.ReadFileTool
import dev.vesta.tools.ReadUrlTool
import dev.vesta.tools.SearchDocsTool
import dev.vesta.tools.SearchFilesTool
import dev.vesta.tools.ShellTool
import dev.vesta.tools.ToolRegistry
import dev.vesta.tools.WebSearchTool
import dev.vesta.tools.WriteFileTool
import dev.vesta.agents.DocsAgent
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.agents.QueryRewriter
import dev.vesta.agents.WebResearchAgent
import dev.vesta.retrieval.WebRetriever
import dev.vesta.storage.WebCacheRepository
import dev.vesta.storage.ExposedRoutingLogRepository
import dev.vesta.decision.OllayaClient
import dev.vesta.router.LlmRouter
import dev.vesta.router.Router
import dev.vesta.web.HttpPageFetcher
import dev.vesta.web.SearxngClient
import dev.vesta.conversation.DefaultConversationService
import dev.vesta.core.AgentType
import dev.vesta.core.UserContext
import dev.vesta.core.UserRole
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.config.VestaConfig
import dev.vesta.core.config.VestaPaths
import dev.vesta.inference.HttpClients
import dev.vesta.inference.ModelManager
import dev.vesta.inference.OllamaAdmin
import dev.vesta.inference.OpenAiCompatibleClient
import dev.vesta.storage.ExposedMessageRepository
import dev.vesta.storage.ExposedSessionRepository
import dev.vesta.storage.KnowledgeRepository
import dev.vesta.knowledge.Indexer
import dev.vesta.retrieval.HttpReranker
import dev.vesta.retrieval.LocalRetriever
import dev.vesta.retrieval.VectorIndex
import dev.vesta.storage.VestaDatabase

/**
 * 구현체 연결 (PROJECT.md 10.1: DI는 app-* 모듈에서). M8에서 app-server도 같은 구성을 쓴다.
 * [generalOverride]가 있으면 설정의 메인 모델 대신 쓴다.
 */
class AppContainer(generalOverride: String? = null) : AutoCloseable {
    val paths = VestaPaths.default()
    val config: VestaConfig = ConfigLoader.load(paths.configFile).let { loaded ->
        if (generalOverride == null) loaded
        else loaded.copy(models = loaded.models.copy(general = (loaded.models.general ?: DEFAULT_GENERAL).copy(name = generalOverride)))
    }

    private val http = HttpClients.local(config.inference.requestTimeoutSeconds)
    /** 평가 명령이 결정 모델을 바꿔 가며 만들 때 쓴다 */
    val httpForEval get() = http
    private val database = VestaDatabase.open(paths.database).also { it.migrate() }

    /** 단일 사용자 동안은 OWNER 한 명 (PROJECT.md 7.12). */
    val owner = UserContext(database.ensureOwner(System.getProperty("user.name") ?: "owner"), UserRole.OWNER)

    val sessions = ExposedSessionRepository(database.db)
    /** 웹 UI 비밀번호·로그인 세션 (M9) */
    val webAuth = dev.vesta.storage.WebAuthRepository(database.db)
    val messages = ExposedMessageRepository(database.db)
    val inference = OpenAiCompatibleClient(config.inference.baseUrl, http)
    val modelManager = ModelManager(
        models = config.models,
        budgetBytes = MacSystem.totalMemoryBytes() * config.memory.budgetPercent / 100,
        runtime = OllamaAdmin(config.inference.ollamaUrl, http),
    )
    val knowledge = KnowledgeRepository(database.db)
    val indexer = Indexer(knowledge, inference)
    /** 리랭커는 선택 (models.reranker, engine: llama-server). 없으면 RRF 순서만 쓴다. */
    val reranker = config.models.reranker?.takeIf { it.baseUrl != null }?.let { HttpReranker(it.baseUrl!!, it.name, http) }
    val retriever = LocalRetriever(knowledge, inference, VectorIndex(knowledge), reranker)

    /** 외부 웹용 HTTP 클라이언트 (리다이렉트 따라감). 로컬 엔진용과 분리한다. */
    private val webHttp = HttpClients.web(config.web.pageTimeoutSeconds)
    val webCache = WebCacheRepository(database.db)
    private val searxng = SearxngClient(config.web.searxngUrl, http, webCache)
    private val pageFetcher = HttpPageFetcher(webHttp, config.web, webCache)
    val webRetriever = WebRetriever(
        search = searxng,
        fetcher = pageFetcher,
        inference = inference,
        embeddingModel = config.models.embedding?.name ?: DEFAULT_EMBEDDING,
        maxPages = config.web.maxPages,
    )
    /** 파일 도구·셸 허용 폴더 = 설정 + 등록한 코드 저장소 루트 (T-04) */
    val sandbox = PathSandbox(
        config.tools.allowedPaths + kotlinx.coroutines.runBlocking {
            knowledge.listCollections(owner.userId).mapNotNull { it.rootPath }
        },
    )
    val toolRegistry = ToolRegistry(
        buildList {
            add(CurrentTimeTool())
            add(ListFilesTool(sandbox))
            add(ReadFileTool(sandbox, config.tools.maxOutputChars))
            add(SearchFilesTool(sandbox))
            add(SearchDocsTool(knowledge, retriever))
            // 웹 검색이 꺼져 있으면 외부로 나가는 도구 자체를 주지 않는다 (5.4)
            if (config.web.mode != WebMode.OFF) {
                add(WebSearchTool(searxng))
                add(ReadUrlTool(pageFetcher, config.tools.maxOutputChars))
            }
            add(WriteFileTool(sandbox))
            add(MakeDirectoryTool(sandbox))
            add(MoveFileTool(sandbox))
            add(dev.vesta.tools.ApplyPatchTool(sandbox))
            if (config.tools.shellEnabled) add(ShellTool(sandbox, config.tools.shellTimeoutSeconds, config.tools.maxOutputChars))
        },
    )
    val approvals = InMemoryApprovalBroker()

    /** Apple 앱 (Orchard MCP, PROJECT.md 7.7a). 실행 파일이 있을 때만. 서버는 처음 쓸 때 띄운다. */
    val orchard: McpToolSource? = config.mcp.servers["orchard"]
        ?.takeIf { it.enabled && java.io.File(it.command).canExecute() }
        ?.let { McpToolSource("orchard", it, config.tools.maxOutputChars) }
    private val decisionClient = OllayaClient(config.decision.baseUrl, config.decision.model, http)
    private val appGroups = AppGroupSelector(decisionClient.takeIf { config.decision.enabled })
    /** D4·D6·D7·D8·D10 판정 (PROJECT.md 4.4, M7). 결정 모델을 끄면 판정도 건너뛴다 */
    val judge = if (config.decision.enabled) dev.vesta.decision.Judge(decisionClient) else null
    val rewriter = QueryRewriter(inference, modelManager)

    val router = Router(
        config = config.decision,
        decision = decisionClient,
        llm = LlmRouter(inference, modelManager),
    )
    val conversation = DefaultConversationService(
        sessions = sessions,
        messages = messages,
        router = router,
        routingLog = ExposedRoutingLogRepository(database.db),
        // 문서 컬렉션이 있어야 DOCS로 보낸다
        availability = { user ->
            buildSet {
                addAll(listOf(AgentType.CHAT, AgentType.WEB, AgentType.CODE, AgentType.TASK))
                // Apple 앱은 Mac 주인 계정의 데이터 → OWNER만 (7.12)
                if (orchard != null && user.role == UserRole.OWNER) add(AgentType.PERSONAL)
                if (knowledge.listCollections(user.userId).any { it.kind == CollectionKind.DOCS }) add(AgentType.DOCS)
            }
        },
        // @이름이 모두 코드 저장소면 코드 도우미로
        mentionAgent = { user, names ->
            val named = knowledge.listCollections(user.userId).filter { it.name in names }
            if (named.isNotEmpty() && named.all { it.kind == CollectionKind.CODE }) AgentType.CODE else null
        },
        approvals = approvals,
        agents = mapOf(
            AgentType.CHAT to ChatAgent(inference, modelManager),
            AgentType.DOCS to DocsAgent(inference, modelManager, knowledge, retriever, rewriter = rewriter, judge = judge),
            AgentType.CODE to CodeAgent(inference, modelManager, knowledge, retriever, judge = judge),
            AgentType.PERSONAL to TaskAgent(
                inference, modelManager, toolRegistry, approvals, ExposedToolRunLog(database.db),
                maxSteps = config.tools.maxSteps, approvalTimeoutSeconds = config.tools.approvalTimeoutSeconds,
                maxToolOutputChars = config.tools.maxOutputChars,
                type = AgentType.PERSONAL,
                rules = Prompts.PERSONAL_RULES,
                // D11로 고른 앱 그룹의 Orchard 도구만 준다
                toolsFor = { request ->
                    val all = orchard?.tools().orEmpty()
                    val groups = appGroups.select(request.history.last().content, all.map { it.group }.toSet())
                    all.filter { it.group in groups }
                },
                judge = judge,
            ),
            AgentType.TASK to TaskAgent(
                inference, modelManager, toolRegistry, approvals, ExposedToolRunLog(database.db),
                maxSteps = config.tools.maxSteps, approvalTimeoutSeconds = config.tools.approvalTimeoutSeconds,
                maxToolOutputChars = config.tools.maxOutputChars,
                judge = judge,
            ),
            AgentType.WEB to WebResearchAgent(inference, modelManager, rewriter, webRetriever, { config.web.mode }, judge = judge),
        ),
    )


    override fun close() {
        http.close()
        webHttp.close()
        orchard?.close()
    }

    companion object {
        /** 설정에 메인 모델이 없을 때 --model과 함께 쓰는 기본 파라미터 (M0 벤치마크: 생각 양 low). */
        val DEFAULT_GENERAL = ModelSpec(name = "", keepAlive = "forever", reasoningEffort = "none")
        const val DEFAULT_EMBEDDING = "bge-m3:567m"
    }
}
