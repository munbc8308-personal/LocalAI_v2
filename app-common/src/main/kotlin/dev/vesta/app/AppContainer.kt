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
    val config: VestaConfig = ConfigLoader.load(paths.configFile, paths.tunedFile).let { loaded ->
        if (generalOverride == null) loaded
        else {
            // 코드 모델이 메인 모델과 같으면 함께 바꾼다 (주간 점검이 후보를 평가할 때, M16)
            val general = loaded.models.general ?: DEFAULT_GENERAL
            val code = loaded.models.code?.let { if (it.name == general.name) it.copy(name = generalOverride) else it }
            loaded.copy(models = loaded.models.copy(general = general.copy(name = generalOverride), code = code))
        }
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
    /** 메신저 계정 연결 (M10) */
    val channelBindings = dev.vesta.storage.ExposedChannelBindingStore(database.db)
    val messages = ExposedMessageRepository(database.db)
    /** 답변 피드백·학습 기록·모델 버전 (M14) */
    val feedback = dev.vesta.storage.ExposedFeedbackRepository(database.db)
    val trainingRuns = dev.vesta.storage.ExposedTrainingRepository(database.db)
    val modelVersions = dev.vesta.storage.ExposedModelVersionRepository(database.db)
    /** 주간 자가 개선 (M16) */
    val evolutionRepository = dev.vesta.storage.ExposedEvolutionRepository(database.db)
    val evolution = dev.vesta.evolution.EvolutionService(evolutionRepository, paths.tunedFile, java.time.Duration.ofDays(config.evolution.proposalTtlDays.toLong()))
    /** 질문에 붙인 이미지 등 (M12) */
    val mediaStore = dev.vesta.storage.FileMediaStore(database.db, paths.home.resolve("media"))
    val ffmpeg = dev.vesta.media.Ffmpeg(config.media.ffmpeg)
    /** 음성 인식: 미디어 워커 (models.stt, engine: mlx-whisper) */
    val speechToText: dev.vesta.media.WorkerSpeechToText? = config.models.stt?.takeIf { it.baseUrl != null }
        ?.let { dev.vesta.media.WorkerSpeechToText(it.baseUrl!!, http, ffmpeg, config.media.maxAudioSeconds) }
    /** 이미지 생성: 미디어 워커의 mflux (models.image, engine: mflux) */
    val imageGenerator: dev.vesta.core.media.ImageGenerator? = config.models.image?.takeIf { it.engine == "mflux" && it.baseUrl != null }
        ?.let { dev.vesta.media.WorkerImageGenerator(it.baseUrl!!, http, it.name) }
    /** 읽어 주기: macOS say (models.tts, engine: say) */
    val textToSpeech: dev.vesta.core.media.TextToSpeech? = config.models.tts?.takeIf { it.engine == "say" }
        ?.let { dev.vesta.media.SayTextToSpeech(it.name, ffmpeg, config.media.ttsMaxChars) }
    /** 이어 말하기 (models.tts가 say일 때, 도우미가 있으면, M19) */
    val streamingSpeech: dev.vesta.core.media.StreamingSpeech? = config.models.tts?.takeIf { it.engine == "say" }?.let { tts ->
        java.nio.file.Path.of(config.media.speakBinary.replaceFirst(Regex("^~"), System.getProperty("user.home")))
            .takeIf { dev.vesta.media.AvSpeechStreaming.available(it) }
            ?.let { dev.vesta.media.AvSpeechStreaming(it, tts.name) }
    }
    val inference = OpenAiCompatibleClient(config.inference.baseUrl, http)
    val modelManager = ModelManager(
        models = config.models,
        budgetBytes = MacSystem.totalMemoryBytes() * config.memory.budgetPercent / 100,
        runtime = OllamaAdmin(config.inference.ollamaUrl, http),
        // 미디어 워커가 올린 음성·이미지 모델 전체
        externalBytes = { speechToText?.health()?.bytes ?: 0L },
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
    /** 파일 도구·셸 허용 폴더 = 설정 + 등록한 코드 저장소 루트 (T-04). OWNER용 */
    val sandbox = PathSandbox(
        config.tools.allowedPaths + kotlinx.coroutines.runBlocking {
            knowledge.listCollections(owner.userId).mapNotNull { it.rootPath }
        },
    )

    /** 도구 모음. 셸은 [shell]일 때만 (역할 정책 U-06) */
    private fun tools(sandbox: PathSandbox, shell: Boolean) = ToolRegistry(
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
            if (shell && config.tools.shellEnabled) add(ShellTool(sandbox, config.tools.shellTimeoutSeconds, config.tools.maxOutputChars))
        },
    )
    val toolRegistry = tools(sandbox, shell = true)

    /** 사용자 (M15) */
    val userRepository = dev.vesta.storage.ExposedUserRepository(database.db)
    val users = dev.vesta.identity.UserService(
        userRepository, webAuth,
        deleteMedia = { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { paths.home.resolve("media").resolve(id.value.toString()).toFile().deleteRecursively() } },
    )
    private val memberTools = java.util.concurrent.ConcurrentHashMap<Long, ToolRegistry>()
    private val workspaceTools = java.util.concurrent.ConcurrentHashMap<String, ToolRegistry>()

    /**
     * 이 사용자에게 줄 도구 (U-06, T-04). OWNER는 전부, MEMBER는 셸 없이 자기 폴더(`users.memberRoot/<이름>` + `users.paths`)만,
     * GUEST는 없음. 역할이 바뀌면 다음 요청부터 반영된다
     */
    suspend fun toolsFor(user: UserContext, workspace: dev.vesta.core.agent.Workspace? = null): List<dev.vesta.core.tools.Tool> = when (user.role) {
        // CLI 작업 폴더(M17)가 있으면 그 폴더를 첫 허용 폴더로 둔다: 상대 경로·셸 작업 위치가 그 폴더가 된다
        UserRole.OWNER -> workspace?.let { ws ->
            workspaceTools.getOrPut(ws.path) { tools(PathSandbox(listOf(ws.path) + sandbox.roots.map { it.toString() }), shell = true) }.all
        } ?: toolRegistry.all
        UserRole.GUEST -> emptyList()
        UserRole.MEMBER -> {
            val name = userRepository.get(user.userId)?.name ?: return emptyList()
            memberTools.getOrPut(user.userId.value) {
                val root = java.nio.file.Path.of(config.users.memberRoot.replaceFirst(Regex("^~"), System.getProperty("user.home"))).toAbsolutePath().normalize()
                val home = root.resolve(name).normalize()
                // 이름 규칙(UserService)이 막지만, 폴더가 memberRoot 밖이 되면 도구를 주지 않는다
                if (home.parent != root) return emptyList()
                runCatching { java.nio.file.Files.createDirectories(home) }
                tools(PathSandbox(listOf(home.toString()) + config.users.paths[name].orEmpty()), shell = false)
            }.all.filter { dev.vesta.identity.RolePolicy.allowsRisk(user.role, it.risk) }
        }
    }
    val approvals = InMemoryApprovalBroker()

    /** Apple 앱 (Orchard MCP, PROJECT.md 7.7a). 실행 파일이 있을 때만. 서버는 처음 쓸 때 띄운다. */
    val orchard: McpToolSource? = config.mcp.servers["orchard"]
        ?.takeIf { it.enabled && java.io.File(it.command).canExecute() }
        ?.let { McpToolSource("orchard", it, config.tools.maxOutputChars) }
    /** 학습으로 만든 결정 모델이 켜져 있으면 그것, 아니면 설정의 모델 (L-07). 10초마다 다시 읽는다 */
    val decisionModel = DecisionModelSelector(config.decision.model) { modelVersions.active("decision")?.name }
    private val decisionClient = OllayaClient(config.decision.baseUrl, { decisionModel.current() }, http)
    private val appGroups = AppGroupSelector(decisionClient.takeIf { config.decision.enabled })
    /** D4·D6·D7·D8·D10 판정 (PROJECT.md 4.4, M7). 결정 모델을 끄면 판정도 건너뛴다 */
    val judge = if (config.decision.enabled) dev.vesta.decision.Judge(decisionClient) else null
    val rewriter = QueryRewriter(inference, modelManager)

    val router = Router(
        config = config.decision,
        decision = decisionClient,
        llm = LlmRouter(inference, modelManager),
    )
    /** 요청 큐 (U-05). 서버가 설정을 다시 읽으려고 다시 시작할 때 하던 요청이 없는지 본다 (M16) */
    val scheduler = dev.vesta.identity.FairScheduler(config.users.maxParallel)
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
                if (dev.vesta.identity.RolePolicy.usesLocalData(user.role) && knowledge.accessibleCollections(user.userId).any { it.kind == CollectionKind.DOCS }) add(AgentType.DOCS)
                if (imageGenerator != null) add(AgentType.IMAGE)
            }
        },
        // @이름이 모두 코드 저장소면 코드 도우미로
        mentionAgent = { user, names ->
            val named = if (dev.vesta.identity.RolePolicy.usesLocalData(user.role)) knowledge.accessibleCollections(user.userId).filter { it.name in names } else emptyList()
            if (named.isNotEmpty() && named.all { it.kind == CollectionKind.CODE }) AgentType.CODE else null
        },
        approvals = approvals,
        media = mediaStore,
        scheduler = scheduler,
        agents = mapOf(
            AgentType.CHAT to ChatAgent(inference, modelManager),
            AgentType.DOCS to DocsAgent(inference, modelManager, knowledge, retriever, rewriter = rewriter, judge = judge),
            AgentType.CODE to CodeAgent(inference, modelManager, knowledge, retriever, judge = judge, unanswerable = config.decision.thresholds.codeSufficient),
            AgentType.PERSONAL to TaskAgent(
                inference, modelManager, toolRegistry, approvals, ExposedToolRunLog(database.db),
                maxSteps = config.tools.maxSteps, approvalTimeoutSeconds = config.tools.approvalTimeoutSeconds,
                maxToolOutputChars = config.tools.maxOutputChars,
                type = AgentType.PERSONAL,
                rules = Prompts.PERSONAL_RULES,
                // D11로 고른 앱 그룹의 Orchard 도구만 준다
                toolsFor = { request ->
                    // Apple 앱은 Mac 주인만 (7.12). 라우팅에서 이미 막지만 도구 단계에서도 확인한다
                    if (request.user.role != UserRole.OWNER) return@TaskAgent emptyList()
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
                toolsFor = { request -> toolsFor(request.user, request.workspace) },
                judge = judge,
            ),
            AgentType.IMAGE to (imageGenerator?.let { generator ->
                dev.vesta.agents.ImageAgent(
                    inference, modelManager, generator, mediaStore,
                    reserve = { modelManager.reserve(config.media.imageMemoryGigabytes * 1024L * 1024 * 1024) },
                    size = config.media.imageSize, steps = config.media.imageSteps,
                )
            } ?: ChatAgent(inference, modelManager)),
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

/** 지금 쓸 결정 모델 이름. DB의 활성 버전을 잠깐 기억해 매 판정마다 DB를 읽지 않는다 */
class DecisionModelSelector(private val configured: String, private val active: suspend () -> String?) {
    @Volatile private var cached: String? = null
    @Volatile private var readAt = 0L

    suspend fun current(): String {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - readAt < 10_000 }?.let { return it }
        val name = runCatching { active() }.getOrNull() ?: configured
        cached = name
        readAt = now
        return name
    }

    /** 버전을 바꾼 직후 바로 쓰게 */
    fun refresh() { readAt = 0 }
}
