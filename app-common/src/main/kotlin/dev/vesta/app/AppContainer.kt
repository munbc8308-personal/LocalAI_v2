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
    /** 학습 재료 (M25 L-25) */
    val trainingSamples = dev.vesta.storage.ExposedTrainingSampleRepository(database.db)
    val curatorSource = dev.vesta.storage.ExposedCuratorSource(database.db)
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
    /** 사용자별 설정 (U-07, M20) */
    val userSettings = dev.vesta.storage.ExposedUserSettingsRepository(database.db)
    /** 에이전트 스튜디오 (M22): 사용자 정의 에이전트, 실행 기록 */
    val customAgentRepository = dev.vesta.storage.ExposedCustomAgentRepository(database.db)
    val teamRepository = dev.vesta.storage.ExposedTeamRepository(database.db)
    val a2aRepository = dev.vesta.storage.ExposedA2aAgentRepository(database.db)
    val skillRepository = dev.vesta.storage.ExposedSkillRepository(database.db)
    /** A2A (A-15): 바깥 에이전트. 리다이렉트는 따라가지 않는다 (등록한 주소로만) */
    val a2aClient = dev.vesta.a2a.A2aClient(dev.vesta.a2a.A2aHttp.external(dev.vesta.web.UrlGuard::isInternal), dev.vesta.a2a.A2aHttp.local())
    val runRepository = dev.vesta.storage.ExposedRunRepository(database.db)
    val journal = StudioJournal(runRepository)

    /** 고를 수 있는 읽어 주기 목소리: 이 Mac의 한국어 `say` 음성 (처음 물을 때 한 번 읽는다) */
    val voices: List<String> by lazy {
        if (config.models.tts?.engine != "say") emptyList()
        else runCatching {
            val process = ProcessBuilder("/usr/bin/say", "-v", "?").redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            // "Yuna (Premium)      ko_KR    # 안녕하세요…" → 이름. 이름과 언어 사이는 공백 두 칸 이상
            lines.mapNotNull { line -> Regex("^(.+?)\\s{2,}(ko_KR)\\s").find(line)?.groupValues?.get(1)?.trim() }
                // "Eddy (한국어(한국))" → "Eddy". "Yuna (Premium)"은 그대로 (say -v로 고를 수 있는 이름)
                .map { it.replace(Regex("\\s*\\(한국어.*\\)$"), "") }.distinct()
        }.getOrDefault(emptyList())
    }
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
                // 셸은 자기 폴더 안에서만, 실행마다 Mac 주인 승인 (TaskAgent가 주인에게 넘긴다, M20)
                tools(PathSandbox(listOf(home.toString()) + config.users.paths[name].orEmpty()), shell = config.users.memberShell)
            }.all.filter { dev.vesta.identity.RolePolicy.allowsRisk(user.role, it.risk) || (config.users.memberShell && it is ShellTool) }
        }
    }
    val approvals = InMemoryApprovalBroker()
    /** 주인이 아닌 사용자의 셸 승인을 넘길 곳. 서버가 메신저를 띄운 뒤 채운다 (M20) */
    val escalation = EscalationRelay()

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
        persona = { id -> userSettings.get(id).persona() },
        customAgents = { user, name -> customAgent(user, name) ?: a2aAgent(user, name) },
        teams = { user, name -> team(user, name) },
        skills = { user, workspace -> skillsFor(user, workspace) },
        skillAgent = { user, skill -> skillAgent(skill) },
        directory = { user, kind, workspace -> directory(user, kind, workspace) },
        journal = journal,
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
                escalation = escalation,
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


    /**
     * 사용자 정의 에이전트 (A-11, M22): 작업 에이전트에 역할·지침을 얹고, 도구는 그 사용자가 쓸 수 있는 것 중 고른 것만.
     * 셸·쓰기 승인, MEMBER 셸의 주인 승인은 그대로
     */
    private suspend fun customAgent(
        user: UserContext,
        name: String,
        /** 팀 리더면 멤버에게 맡기는 도구와 팀 규칙 */
        delegate: dev.vesta.agents.DelegateTool? = null,
        teamRules: String = "",
    ): dev.vesta.core.agent.Agent? {
        val def = customAgentRepository.find(user.userId, name) ?: return null
        val rules = Prompts.TASK_RULES + "\n\n너는 사용자 정의 에이전트 '${def.name}'이다. 역할: ${def.description}\n지침:\n${def.instructions.take(dev.vesta.core.studio.CustomAgentDef.MAX_INSTRUCTIONS)}" + teamRules
        return TaskAgent(
            inference, modelManager, toolRegistry, approvals, ExposedToolRunLog(database.db),
            maxSteps = config.tools.maxSteps, approvalTimeoutSeconds = config.tools.approvalTimeoutSeconds,
            maxToolOutputChars = config.tools.maxOutputChars,
            rules = rules,
            toolsFor = { request -> toolsFor(request.user, request.workspace).filter { it.spec.name in def.tools } + listOfNotNull(delegate) },
            judge = judge,
            escalation = escalation,
            modelRole = if (def.model == dev.vesta.core.ModelRole.CODE) dev.vesta.core.ModelRole.CODE else dev.vesta.core.ModelRole.GENERAL,
        )
    }

    /**
     * 팀 (A-14, M22): 리더 에이전트에 `delegate` 도구와 멤버 소개를 얹는다. 멤버는 맡길 때마다 새로 만든다 (고친 지침이 바로 반영).
     * 멤버는 다시 나눠 맡기지 않는다. 리더·멤버 모두 그 사용자 역할의 도구 안에서만
     */
    private suspend fun team(user: UserContext, name: String): dev.vesta.core.agent.Agent? {
        val def = teamRepository.find(user.userId, name) ?: return null
        // 멤버는 사용자 정의 에이전트 또는 A2A 에이전트 (A2A는 주인만)
        val a2a = if (user.role == UserRole.OWNER) a2aRepository.list(user.userId).associateBy { it.name } else emptyMap()
        val members = def.members.mapNotNull { member ->
            customAgentRepository.find(user.userId, member)?.let { m -> dev.vesta.agents.TeamMember(m.name, m.description) { customAgent(user, m.name) } }
                ?: a2a[member]?.let { m -> dev.vesta.agents.TeamMember(m.name, "(A2A 바깥 에이전트 ${m.displayName}) ${m.description}".take(300)) { a2aAgent(user, m.name) } }
        }
        if (members.isEmpty()) return null
        val rules = "\n\n너는 팀 '${def.name}'(${def.description})의 리더다. 멤버:\n" + members.joinToString("\n") { "- ${it.name}: ${it.description}" } +
            "\n- 일을 나눠 delegate 도구로 멤버에게 맡긴다. 한 번에 한 멤버, 앞 멤버의 결과가 필요하면 다음 task에 그 결과를 적어 넘긴다." +
            "\n- 멤버는 이 대화를 보지 못한다. task에 목표·조건·경로·앞 단계 결과를 모두 적는다." +
            "\n- 멤버의 결과를 그대로 믿지 말고 확인한 뒤, 무엇을 누가 했는지 짧게 밝혀 최종 답을 쓴다."
        return customAgent(user, def.leader, dev.vesta.agents.DelegateTool(members), rules)
    }

    /**
     * 부를 수 있는 스킬 (K-01): 작업 폴더(CLI가 승인받은 것) > 내 파일(`~/.vesta/skills/<이름>/SKILL.md`) > 웹에서 만든 것.
     * 작업 폴더·내 파일은 Mac 주인 것이라 Mac 주인만 쓴다
     */
    suspend fun skillsFor(user: UserContext, workspace: dev.vesta.core.agent.Workspace? = null): List<dev.vesta.core.skill.Skill> {
        val owner = user.role == UserRole.OWNER
        val project = if (owner) workspace?.skills.orEmpty() else emptyList()
        val personal = if (owner) personalSkills() else emptyList()
        return (project + personal + skillRepository.list(user.userId)).distinctBy { it.name }
    }

    /** `~/.vesta/skills/<이름>/SKILL.md`. 형식이 틀리거나 숨은 문자가 있는 파일은 건너뛴다 */
    fun personalSkills(): List<dev.vesta.core.skill.Skill> = runCatching {
        val root = paths.home.resolve("skills")
        if (!java.nio.file.Files.isDirectory(root)) return emptyList()
        java.nio.file.Files.list(root).use { dirs ->
            dirs.toList().sorted().take(MAX_SKILLS).mapNotNull { dir ->
                val file = dir.resolve("SKILL.md")
                if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > 64_000) return@mapNotNull null
                dev.vesta.core.skill.Skill.parse(dir.fileName.toString(), java.nio.file.Files.readString(file), dev.vesta.core.skill.SkillSource.PERSONAL,
                    java.nio.file.Files.getLastModifiedTime(file).toInstant())
            }
        }
    }.getOrDefault(emptyList())

    /** 작업 에이전트 스킬: 스킬이 적은 도구 ∩ 그 사용자가 쓸 수 있는 도구 (K-06). 셸·쓰기 승인은 그대로 */
    private fun skillAgent(skill: dev.vesta.core.skill.Skill): dev.vesta.core.agent.Agent = TaskAgent(
        inference, modelManager, toolRegistry, approvals, ExposedToolRunLog(database.db),
        maxSteps = config.tools.maxSteps, approvalTimeoutSeconds = config.tools.approvalTimeoutSeconds,
        maxToolOutputChars = config.tools.maxOutputChars,
        rules = Prompts.TASK_RULES + "\n\n이번 요청은 스킬 '/${skill.name}'이다. 요청 첫머리의 [스킬] 지침대로 일한다.",
        toolsFor = { request -> toolsFor(request.user, request.workspace).filter { it.spec.name in skill.tools } },
        judge = judge,
        escalation = escalation,
    )

    /** `/skills`·`/agents` 목록 */
    private suspend fun directory(user: UserContext, kind: String, workspace: dev.vesta.core.agent.Workspace?): String = when (kind) {
        "skills" -> skillsFor(user, workspace).takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "부를 수 있는 스킬 (`/이름 할 일`):\n") { s ->
            val from = when (s.source) { dev.vesta.core.skill.SkillSource.PROJECT -> "작업 폴더"; dev.vesta.core.skill.SkillSource.PERSONAL -> "내 파일"; else -> "웹" }
            "- /${s.name} — ${s.description} (${from} · ${s.agent.name.lowercase()}" + (if (s.tools.isNotEmpty()) " · 도구 ${s.tools.joinToString()}" else "") + ")"
        } ?: "스킬이 없습니다. 웹 UI 에이전트 탭에서 만들거나, ~/.vesta/skills/<이름>/SKILL.md 파일로 둘 수 있습니다."
        else -> buildString {
            val custom = customAgentRepository.list(user.userId)
            val teams = teamRepository.list(user.userId)
            val remote = if (user.role == UserRole.OWNER) a2aRepository.list(user.userId) else emptyList()
            if (custom.isEmpty() && teams.isEmpty() && remote.isEmpty()) append("만든 에이전트가 없습니다. 웹 UI 에이전트 탭에서 만들 수 있습니다.")
            if (custom.isNotEmpty()) append(custom.joinToString("\n", prefix = "에이전트 (`/agent 이름 할 일`):\n") { "- ${it.name} — ${it.description}" })
            if (remote.isNotEmpty()) append((if (isNotEmpty()) "\n\n" else "") + remote.joinToString("\n", prefix = "A2A 에이전트 (`/agent 이름 할 일`):\n") { "- ${it.name} — ${it.displayName}: ${it.description.take(100)}" })
            if (teams.isNotEmpty()) append((if (isNotEmpty()) "\n\n" else "") + teams.joinToString("\n", prefix = "팀 (`/team 이름 할 일`):\n") { "- ${it.name} — ${it.description} (리더 ${it.leader}, 멤버 ${it.members.joinToString()})" })
        }
    }

    /**
     * 👍·👎·담당 정정 저장 (웹·메신저 공통, C-09·L-20). 동의한 사용자(L-09)면 👍·👎를 바로 학습 재료로 (확실한 신호라 검토 없이).
     * 0(평가 취소)이면 그 재료를 빼고, 바꾸면 고친다
     */
    suspend fun recordFeedback(user: UserContext, value: dev.vesta.core.training.Feedback): Boolean {
        if (!feedback.save(user.userId, value)) return false
        if (user.userId !in users.trainingUsers()) return true
        val turn = curatorSource.turnOfAnswer(value.messageId) ?: return true
        // 담당 정정만 남긴 경우(평가 없음)는 라우팅 정정으로 이미 학습에 들어간다
        if (value.rating == 0 && value.correctAgent != null) return true
        trainingSamples.add(
            dev.vesta.core.training.TrainingSample(
                id = 0, userId = user.userId, kind = dev.vesta.core.training.SampleKind.ANSWER, signal = dev.vesta.core.training.SampleSignal.FEEDBACK,
                messageId = value.messageId,
                question = dev.vesta.training.PrivacyFilter.clean(turn.question).take(2_000),
                answer = turn.answer?.let { dev.vesta.training.PrivacyFilter.clean(it).take(4_000) },
                label = when { value.rating > 0 -> "good"; value.rating < 0 -> "bad"; else -> "none" },
                confidence = 1.0,
                status = if (value.rating == 0) dev.vesta.core.training.SampleStatus.REJECTED else dev.vesta.core.training.SampleStatus.ACCEPTED,
                note = value.comment?.let { dev.vesta.training.PrivacyFilter.clean(it).take(300) },
            ),
        )
        return true
    }

    /** A2A 에이전트 (A-15): Mac 주인만. 토큰은 키체인 `dev.vesta.a2a` / 이름 */
    private suspend fun a2aAgent(user: UserContext, name: String): dev.vesta.core.agent.Agent? {
        if (user.role != UserRole.OWNER) return null
        val def = a2aRepository.find(user.userId, name) ?: return null
        return dev.vesta.a2a.A2aAgent(def, a2aClient, approvals, { BotTokens.keychain(A2A_KEYCHAIN, def.name) }, config.tools.approvalTimeoutSeconds)
    }

    /** A2A 토큰이 키체인에 있는지 (웹 UI 표시용) */
    fun a2aHasToken(name: String) = BotTokens.keychain(A2A_KEYCHAIN, name) != null

    /** 사용자 정의 에이전트에 줄 수 있는 도구 목록 (이 사용자가 쓸 수 있는 것, 웹 UI 고르기용) */
    suspend fun toolCatalog(user: UserContext): List<dev.vesta.core.tools.Tool> = toolsFor(user)

    override fun close() {
        http.close()
        webHttp.close()
        orchard?.close()
    }

    companion object {
        /** 설정에 메인 모델이 없을 때 --model과 함께 쓰는 기본 파라미터 (M0 벤치마크: 생각 양 low). */
        val DEFAULT_GENERAL = ModelSpec(name = "", keepAlive = "forever", reasoningEffort = "none")
        const val DEFAULT_EMBEDDING = "bge-m3:567m"
        /** A2A 에이전트 토큰의 키체인 서비스 (계정 = 에이전트 이름) */
        const val A2A_KEYCHAIN = "dev.vesta.a2a"
        /** 한 곳에서 읽는 스킬 수 */
        const val MAX_SKILLS = 100
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

/** 셸 승인 넘길 곳을 나중에 채운다 (서버가 메신저를 띄운 뒤, M20). 비어 있으면 거부 */
class EscalationRelay : dev.vesta.core.tools.ApprovalEscalation {
    @Volatile var target: dev.vesta.core.tools.ApprovalEscalation? = null

    override suspend fun ask(requester: UserContext, tool: String, details: dev.vesta.core.tools.ApprovalDetails, reason: String?, timeoutSeconds: Long) =
        target?.ask(requester, tool, details, reason, timeoutSeconds)
            ?: dev.vesta.core.tools.ApprovalDecision.Deny("Mac 주인에게 승인을 물을 곳이 없습니다 (서버 메신저가 꺼져 있음)")
}
