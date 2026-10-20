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
                    reranker?.let { "reranker" to it.name }, vision?.let { "vision" to it.name }, stt?.let { "stt" to it.name }, tts?.let { "tts" to "say ${it.name}" },
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
                ),
                models = models,
                // 미디어 워커가 올린 음성 인식 모델도 메모리 사용량에 보인다
                loaded = loaded.await().map { LoadedModelDto(it.name, it.sizeBytes, it.gpuBytes) } +
                    listOfNotNull(worker.await()?.stt?.takeIf { it.loaded }?.let { LoadedModelDto(it.model.substringAfterLast('/'), it.bytes, it.bytes) }),
                memoryTotalBytes = total,
                memoryBudgetBytes = total * app.config.memory.budgetPercent / 100,
            )
        }
    }
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
        collections = { user ->
            app.knowledge.listCollections(user.userId).map { c ->
                val stats = app.knowledge.stats(c.id)
                CollectionDto(c.name, c.kind.name, c.description, c.rootPath, c.embeddingModel, stats.documents, stats.chunks)
            }
        },
        dashboard = dashboard,
        speechToText = app.speechToText,
        textToSpeech = app.textToSpeech,
        media = app.mediaStore,
        mediaLimits = app.config.media,
        allowedHosts = app.config.server.allowedHosts.map { it.lowercase() }.toSet(),
        serveWebUi = webUi,
    )
    // 메신저 봇 (M10). 서버와 같은 프로세스에서 돈다
    val channels = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    startChannels(app, channels) { dashboard().statusText() }
    Runtime.getRuntime().addShutdownHook(Thread { channels.cancel(); app.close() })
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
