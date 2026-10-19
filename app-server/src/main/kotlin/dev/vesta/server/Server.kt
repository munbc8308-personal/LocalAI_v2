package dev.vesta.server

import dev.vesta.app.ApiToken
import dev.vesta.app.AppContainer
import dev.vesta.inference.OllamaAdmin
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
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
    val deps = ServerDeps(
        conversation = app.conversation,
        sessions = app.sessions,
        messages = app.messages,
        owner = app.owner,
        token = token,
        version = VERSION,
        health = {
            mapOf(
                "ollama" to (withTimeoutOrNull(1_500) { runCatching { "v" + OllamaAdmin(app.config.inference.ollamaUrl, app.httpForEval).version() }.getOrNull() } ?: "응답 없음"),
                "orchard" to if (app.orchard != null) "설정됨" else "없음",
            )
        },
    )
    Runtime.getRuntime().addShutdownHook(Thread { app.close() })
    log.info { "Vesta 서버 시작: http://$host:${app.config.server.port}" }
    embeddedServer(CIO, port = app.config.server.port, host = host) { vestaApi(deps) }.start(wait = true)
}
