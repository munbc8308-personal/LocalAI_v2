package dev.vesta.server

import dev.vesta.api.CollectionDto
import dev.vesta.api.LoadedModelDto
import dev.vesta.app.ApiToken
import dev.vesta.app.AppContainer
import dev.vesta.app.MacSystem
import dev.vesta.inference.OllamaAdmin
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import dev.vesta.api.DashboardDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

private val log = KotlinLogging.logger {}

const val VERSION = "0.1.0"

/**
 * 상시 실행 서버 본체 (PROJECT.md 6.2). launchd `dev.vesta.server`가 띄우고, 죽으면 다시 띄운다.
 * 모델·DB·도구는 이 프로세스 하나가 가지고, CLI·웹 UI·메신저·외부 클라이언트는 HTTP로 붙는다.
 */
fun main() {
    val app = AppContainer()
    val token = ApiToken.readOrCreate()
    val host = app.config.server.host
    if (host != "127.0.0.1" && host != "localhost") log.warn { "서버가 $host 에 열립니다. 외부 접속은 Tailscale만 권장 (PROJECT.md 6.3)" }
    val ollama = OllamaAdmin(app.config.inference.ollamaUrl, app.httpForEval)
    suspend fun probe(block: suspend () -> String): String = withTimeoutOrNull(1_500) { runCatching { block() }.getOrNull() } ?: "응답 없음"
    val webUi = ServerDeps::class.java.classLoader.getResource("web/index.html") != null
    if (!webUi) log.warn { "웹 UI 파일이 없습니다 (web-ui 빌드 결과가 클래스패스 web/ 에 없음)" }
    val dashboard: suspend () -> DashboardDto = {
        coroutineScope {
            val ollamaVersion = async { probe { "v" + ollama.version() } }
            val ollaya = async { probe { check(app.httpForEval.get("${app.config.decision.baseUrl}/api/version").status.isSuccess()); "정상 (${app.config.decision.model})" } }
            val searxng = async { probe { check(app.httpForEval.get("${app.config.web.searxngUrl}/healthz").status.isSuccess()); "정상" } }
            val loaded = async { withTimeoutOrNull(2_000) { runCatching { ollama.loadedModels() }.getOrNull() }.orEmpty() }
            val worker = async { withTimeoutOrNull(1_500) { app.speechToText?.health() } }
            val total = MacSystem.totalMemoryBytes()
            val models = with(app.config.models) {
                listOfNotNull(
                    general?.let { "general" to it.name }, code?.let { "code" to it.name }, embedding?.let { "embedding" to it.name },
                    reranker?.let { "reranker" to it.name }, vision?.let { "vision" to it.name }, image?.let { "image" to it.name }, stt?.let { "stt" to it.name }, tts?.let { "tts" to "say ${it.name}" },
                )
            }.toMap() + ("decision" to app.config.decision.model)
            DashboardDto(
                version = VERSION,
                checks = mapOf(
                    "Ollama" to ollamaVersion.await(),
                    "Ollaya (결정 모델)" to if (app.config.decision.enabled) ollaya.await() else "꺼짐",
                    "SearXNG" to searxng.await(),
                    "Orchard" to if (app.orchard != null) "설정됨" else "없음",
                    "음성 인식 (미디어 워커)" to when {
                        app.speechToText == null -> "꺼짐"
                        else -> worker.await()?.stt?.let { if (it.loaded) "정상 (모델 올라감)" else "정상 (대기, 처음 쓸 때 올림)" } ?: "응답 없음"
                    },
                    "이미지 생성 (미디어 워커)" to when {
                        app.imageGenerator == null -> "꺼짐"
                        else -> worker.await()?.image?.let {
                            when {
                                it.preparing -> "모델 준비 중 (처음 한 번 받기·양자화)"
                                it.loaded -> "정상 (모델 올라감)"
                                it.ready -> "정상 (대기, 처음 쓸 때 올림)"
                                it.error != null -> "모델 없음 (${it.error.orEmpty().take(80)})"
                                else -> "모델 없음 (처음 요청할 때 받음, Hugging Face 로그인 필요)"
                            }
                        } ?: "응답 없음"
                    },
                ),
                models = models,
                // 미디어 워커가 올린 음성 인식 모델도 메모리 사용량에 보인다
                loaded = loaded.await().map { LoadedModelDto(it.name, it.sizeBytes, it.gpuBytes) } +
                    listOfNotNull(worker.await()?.takeIf { it.bytes > 0 }?.let { w ->
                        val names = listOfNotNull(w.stt?.takeIf { it.loaded }?.model?.substringAfterLast('/'), w.image?.takeIf { it.loaded }?.model)
                        LoadedModelDto("미디어 워커 (${names.joinToString().ifEmpty { "캐시" }})", w.bytes, w.bytes)
                    }),
                memoryTotalBytes = total,
                memoryBudgetBytes = total * app.config.memory.budgetPercent / 100,
            )
        }
    }
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val training = trainingEndpoints(app, background)
    // 서버가 꺼지면서 끝나지 못한 실행 정리 (M22)
    kotlinx.coroutines.runBlocking { app.runRepository.abandonRunning() }
    // 주간 자가 개선 (M16): 알림·승인 버튼·설정이 바뀌면 다시 시작. 봇은 아래에서 띄운다
    var notifiers: List<dev.vesta.channels.ChannelNotifier> = emptyList()
    val evolution = EvolutionDesk(
        app.evolutionRepository, app.evolution, app.owner.userId, app.config.evolution.enabled, app.paths.tunedFile,
        notifiers = { notifiers },
        busy = {
            val (running, waiting) = app.scheduler.load()
            running > 0 || waiting > 0 || app.trainingRuns.list(1).firstOrNull()?.status == dev.vesta.core.training.TrainingStatus.RUNNING
        },
    )
    val deps = ServerDeps(
        conversation = app.conversation,
        sessions = app.sessions,
        messages = app.messages,
        owner = app.owner,
        token = token,
        version = VERSION,
        health = {
            mapOf(
                "ollama" to probe { "v" + ollama.version() },
                "orchard" to if (app.orchard != null) "설정됨" else "없음",
            )
        },
        auth = WebAuth(app.webAuth),
        // 내 컬렉션과 공유받은 컬렉션 (U-04). GUEST는 로컬 문서를 쓰지 않는다
        collections = { user ->
            if (!dev.vesta.identity.RolePolicy.usesLocalData(user.role)) emptyList()
            else {
                val names = app.users.list().associate { it.id to it.name }
                app.knowledge.accessibleCollections(user.userId).map { c ->
                    val stats = app.knowledge.stats(c.id)
                    val mine = c.ownerUserId == user.userId
                    CollectionDto(
                        c.name, c.kind.name, c.description, if (mine) c.rootPath else null, c.embeddingModel, stats.documents, stats.chunks,
                        owner = if (mine) null else names[c.ownerUserId],
                        sharedWith = if (mine) c.sharedWith.mapNotNull { names[it] }.sorted() else emptyList(),
                    )
                }
            }
        },
        dashboard = dashboard,
        speechToText = app.speechToText,
        textToSpeech = app.textToSpeech,
        media = app.mediaStore,
        mediaLimits = app.config.media,
        feedback = app.feedback,
        recordFeedback = { user, value -> app.recordFeedback(user, value) },
        training = training,
        users = app.users,
        evolution = evolution,
        userSettings = app.userSettings,
        studio = StudioEndpoints(app.customAgentRepository, app.runRepository, app.journal.updates, { app.toolCatalog(it) }, app.teamRepository, app.a2aRepository, app.a2aClient, { app.a2aHasToken(it) }, app.skillRepository, { app.skillsFor(it) }),
        voices = { app.voices },
        messengers = MessengerEndpoints(
            app.channelBindings, enabled = { notifiers.map { it.channel }.toSet() },
            userName = { app.userRepository.get(it)?.name }, findUser = { app.userRepository.find(it) },
        ),
        allowedHosts = app.config.server.allowedHosts.map { it.lowercase() }.toSet(),
        serveWebUi = webUi,
    )
    // 메신저 봇 (M10). 서버와 같은 프로세스에서 돈다
    val channels = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 셸 승인 넘기기 (M20): MEMBER의 셸 요청을 주인 메신저로
    val ownerApprovals = OwnerApprovals(app.owner.userId, { notifiers }, { app.userRepository.get(it)?.name })
    app.escalation.target = ownerApprovals
    notifiers = startChannels(app, channels, dev.vesta.channels.ChannelActionSet(listOf(evolution, ownerApprovals))) { dashboard().statusText() }
    evolution.start(background)
    Runtime.getRuntime().addShutdownHook(Thread { channels.cancel(); background.cancel(); app.close() })
    log.info { "Vesta 서버 시작: http://$host:${app.config.server.port}" }
    embeddedServer(CIO, port = app.config.server.port, host = host) { vestaApi(deps) }.start(wait = true)
}

/** 메신저 `/status` 답 */
private fun DashboardDto.statusText(): String = buildString {
    append("Vesta ").append(version)
    checks.forEach { (name, value) -> append('\n').append(name).append(": ").append(value) }
    if (loaded.isNotEmpty()) append("\n로드된 모델: ").append(loaded.joinToString { "${it.name} ${gb(it.sizeBytes)}" })
    if (memoryTotalBytes > 0) append("\n메모리 예산: ").append(gb(memoryBudgetBytes)).append(" / ").append(gb(memoryTotalBytes))
}

private fun gb(bytes: Long): String = "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))

/** 결정 모델 학습 (M14). 시작할 때 끝나지 못한 실행을 정리한다 */
private fun trainingEndpoints(app: AppContainer, scope: CoroutineScope): TrainingEndpoints {
    kotlinx.coroutines.runBlocking { app.trainingRuns.abandonRunning() }
    val service = dev.vesta.training.TrainingService(
        config = app.config.training,
        runs = app.trainingRuns,
        versions = app.modelVersions,
        feedback = app.feedback,
        dataUsers = { app.users.trainingUsers() },
        ollayaUrl = app.config.decision.baseUrl,
        scope = scope,
        makeRoom = { bytes ->
            app.speechToText?.unloadAll()
            app.modelManager.reserve(bytes)
        },
        samples = app.trainingSamples,
        codeContext = { app.codeContext(it) },
        embedding = dev.vesta.training.EmbeddingHooks(
            passages = app.passages,
            ollamaUrl = app.config.inference.ollamaUrl,
            current = { app.embeddingModel() },
            switch = { app.embeddingSwitcher.switch(it) },
        ),
    )
    // 학습 재료 큐레이터 (L-25): 쉬는 때만(답하는 중·마지막 대화 뒤 2분 안·학습·주간 점검 중이 아님) 10분마다
    val curator = dev.vesta.training.Curator(
        source = app.curatorSource,
        samples = app.trainingSamples,
        dataUsers = { app.users.trainingUsers() },
        idle = {
            !app.journal.busy && !service.running &&
                java.time.Duration.between(app.journal.lastActivity, java.time.Instant.now()) > java.time.Duration.ofMinutes(2) &&
                app.evolutionRepository.runs(1).firstOrNull()?.status != dev.vesta.core.evolution.EvolutionStatus.RUNNING
        },
        busy = { app.journal.busy || service.running },
        // 검색 재료 (L-23): 조각 → 메인 모델 질문 → D6 검증. 결정 모델이 꺼져 있으면 검증할 수 없어 만들지 않는다
        miner = app.judge?.let { judge ->
            dev.vesta.training.PairMiner(app.passages, app.trainingSamples, ask = { app.questionWriter.ask(it) }, sufficient = { q, text -> judge.sufficient(q, text) })
        },
        // 한 일을 에이전트 스튜디오 타임라인에 (L-27)
        record = { report ->
            val pending = report.added.count { it.status == dev.vesta.core.training.SampleStatus.PENDING }
            app.journal.background(app.owner.userId, "CURATOR", "학습 재료 모으기", listOfNotNull(
                Triple("status", "새 질문 ${report.turns}개를 봤습니다", null).takeIf { report.turns > 0 },
                Triple("sources", "재료 ${report.added.size}개 (검토 대기 $pending)", report.added.joinToString("\n") { "· ${it.kind}/${it.signal} ${it.label} (${"%.2f".format(it.confidence)}): ${it.question.take(80)}" }.ifBlank { null }),
                Triple("sources", "검색 재료: 조각 ${report.pairs.size}개를 보고 ${report.pairs.count { it.status != dev.vesta.core.training.SampleStatus.REJECTED }}개를 남김",
                    report.pairs.joinToString("\n") { "· ${it.status} (${"%.2f".format(it.confidence)}) ${it.question.take(80)} — ${it.note.orEmpty().take(80)}" }).takeIf { report.pairs.isNotEmpty() },
                Triple("status", "누가 써서 멈췄습니다. 다음에 이어 봅니다", null).takeIf { report.stoppedEarly },
            ))
        },
    )
    curator.start(scope)
    fun dev.vesta.core.training.TrainingRun.dto() = dev.vesta.api.TrainingRunDto(
        id, kind, status.name, stage, step, total, loss, candidate, error, report, startedAt.toString(), finishedAt?.toString(),
    )
    return TrainingEndpoints(
        state = {
            dev.vesta.api.TrainingStateDto(
                decisionModel = app.decisionModel.current(),
                baseModel = app.config.decision.model,
                corrections = app.users.trainingUsers().let { users ->
                    users.sumOf { app.feedback.routingCorrections(it).size } + app.trainingSamples.accepted(dev.vesta.core.training.SampleKind.ROUTING, users).size
                },
                runs = app.trainingRuns.list(20).map { it.dto() },
                versions = app.modelVersions.list("decision").map { dev.vesta.api.ModelVersionDto(it.name, it.baseModel, it.active, it.runId, it.createdAt.toString()) },
                samples = app.trainingSamples.counts(),
                embeddingModel = app.embeddingModel(),
                embeddingBase = app.config.training.embeddingBase,
                embeddingVersions = app.modelVersions.list("embedding").map { dev.vesta.api.ModelVersionDto(it.name, it.baseModel, it.active, it.runId, it.createdAt.toString()) },
            )
        },
        curate = { curator.runOnce(force = true)?.let { r -> r.added.size + r.pairs.count { it.status != dev.vesta.core.training.SampleStatus.REJECTED } } ?: 0 },
        samples = { status ->
            val names = app.userRepository.list().associate { it.id to it.name }
            app.trainingSamples.list(status?.let { dev.vesta.core.training.SampleStatus.valueOf(it) }, 100).map {
                dev.vesta.api.TrainingSampleDto(it.id, names[it.userId], it.kind.name, it.signal.name, it.question, it.answer, it.label, it.confidence, it.status.name, it.note, it.createdAt.toString())
            }
        },
        review = { id, dto ->
            // 라우팅 재료를 고칠 때는 있는 담당 이름만
            val label = dto.label?.takeIf { l -> l in dev.vesta.core.AgentType.entries.map { it.name } || l == "good" || l == "bad" }
            if (dto.label != null && label == null) false
            else app.trainingSamples.review(id, dev.vesta.core.training.SampleStatus.valueOf(dto.status), label)
        },
        start = { service.startDecision().dto() },
        startEmbedding = { service.startEmbedding().dto() },
        cancel = { service.cancel(it) },
        // 설정의 기본 모델로 되돌리거나, 학습한 버전 중 하나로 바꾼다 (L-07). 임베딩은 모든 컬렉션 벡터를 그 모델로 다시 만든 뒤 켠다 (L-23)
        activate = { role, name ->
            when (role) {
                "embedding" -> {
                    val known = name == app.config.training.embeddingBase || app.modelVersions.list("embedding").any { it.name == name }
                    if (known && name != app.embeddingModel()) {
                        app.embeddingSwitcher.switch(name)
                        app.modelVersions.activate("embedding", name)
                    }
                    known
                }
                else -> {
                    val known = name == app.config.decision.model || app.modelVersions.list("decision").any { it.name == name }
                    if (known) {
                        app.modelVersions.activate("decision", name)
                        app.decisionModel.refresh()
                    }
                    known
                }
            }
        },
    )
}
