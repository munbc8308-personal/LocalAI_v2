package dev.vesta.channels.discord

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/** 받은 이벤트를 넘겨주는 쪽 (시험에서는 가짜로 바꾼다) */
fun interface DiscordEvents {
    /** 끝날 때까지 이벤트를 [onDispatch]로 넘긴다. 다시 붙어도 소용없으면(토큰 거부 등) 돌아온다 */
    suspend fun run(onDispatch: suspend (type: String, data: JsonObject) -> Unit)
}

/**
 * Discord Gateway (WebSocket) 연결 (PROJECT.md 6.4: 들어오는 연결 없이 받는다).
 * Hello → Identify(또는 Resume) → heartbeat를 주기마다 보내고, ack가 안 오면 끊고 이어 붙는다.
 * 끊기면 세션을 이어 받고(Resume), 이을 수 없으면 새로 Identify 한다.
 */
class DiscordGateway(
    private val token: String,
    private val http: HttpClient,
    private val gatewayUrl: suspend () -> String,
    private val intents: Int = DIRECT_MESSAGES,
) : DiscordEvents {
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var seq: Long? = null

    override suspend fun run(onDispatch: suspend (type: String, data: JsonObject) -> Unit) {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            var ready = false
            val close = try {
                val base = resumeUrl.takeIf { sessionId != null } ?: gatewayUrl()
                connect("${base.trimEnd('/')}/?v=10&encoding=json") { type, data ->
                    if (type == "READY" || type == "RESUMED") ready = true
                    onDispatch(type, data)
                }
            } catch (e: CancellationException) {
                // 우리가 멈춘 게 아니라 연결 쪽 작업이 취소된 것이면(끊김) 다시 붙는다
                currentCoroutineContext().ensureActive()
                log.warn { "Discord Gateway 연결이 끊겼습니다" }
                null
            } catch (e: Exception) {
                log.warn { "Discord Gateway 연결 실패: ${e::class.simpleName}: ${e.message?.replace(token, "<token>")}" }
                null
            }
            val code = close?.code?.toInt()
            when (code) {
                in FATAL -> {
                    log.error { "Discord Gateway가 연결을 거부했습니다 ($code ${close?.message}). " + if (code == 4004) "vesta discord token 으로 토큰을 다시 저장하세요" else "설정을 확인하세요" }
                    return
                }
                // 이 세션은 이어 받을 수 없다 → 새로 Identify
                4007, 4009 -> clearSession()
            }
            if (ready) backoff = 1_000L
            log.info { "Discord Gateway 다시 연결 (${code ?: "-"} ${close?.message.orEmpty()}), ${backoff}ms 뒤" }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    private fun clearSession() {
        sessionId = null
        resumeUrl = null
        seq = null
    }

    /** 연결 하나. 끊긴 이유를 돌려준다 */
    private suspend fun connect(url: String, onDispatch: suspend (String, JsonObject) -> Unit): CloseReason? {
        var reason: CloseReason? = null
        http.webSocket(url) {
            converse(onDispatch)
            reason = closeReason.await()
        }
        return reason
    }

    private suspend fun DefaultClientWebSocketSession.converse(onDispatch: suspend (String, JsonObject) -> Unit) {
        val hello = receiveObject() ?: return
        if (hello["op"]?.jsonPrimitive?.intOrNull != HELLO) {
            close(CloseReason(4000, "no hello"))
            return
        }
        val interval = hello["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_250L
        var acked = true
        val heartbeat = launch {
            // 처음 한 번은 주기 안의 임의 시점에 (여러 클라이언트가 한꺼번에 보내지 않게)
            delay((interval * Random.nextDouble()).toLong())
            while (isActive) {
                if (!acked) {
                    log.warn { "Discord heartbeat 응답 없음 → 다시 연결" }
                    // 1000·1001로 닫으면 세션이 사라지므로 다른 코드로 닫는다
                    close(CloseReason(4000, "heartbeat timeout"))
                    return@launch
                }
                acked = false
                sendOp(HEARTBEAT, seq?.let(::JsonPrimitive) ?: JsonNull)
                delay(interval)
            }
        }
        val session = sessionId
        if (session != null) {
            sendOp(RESUME, buildJsonObject { put("token", token); put("session_id", session); put("seq", seq) })
        } else {
            sendOp(IDENTIFY, buildJsonObject {
                put("token", token)
                put("intents", intents)
                putJsonObject("properties") { put("os", "macos"); put("browser", "vesta"); put("device", "vesta") }
            })
        }
        try {
            loop@ for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val payload = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                when (payload["op"]?.jsonPrimitive?.intOrNull) {
                    DISPATCH -> {
                        payload["s"]?.jsonPrimitive?.longOrNull?.let { seq = it }
                        val type = payload["t"]?.jsonPrimitive?.contentOrNull ?: continue@loop
                        val data = payload["d"] as? JsonObject ?: JsonObject(emptyMap())
                        if (type == "READY") {
                            sessionId = data["session_id"]?.jsonPrimitive?.contentOrNull
                            resumeUrl = data["resume_gateway_url"]?.jsonPrimitive?.contentOrNull
                        }
                        try {
                            onDispatch(type, data)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error(e) { "Discord 이벤트 처리 실패 ($type)" }
                        }
                    }
                    HEARTBEAT -> sendOp(HEARTBEAT, seq?.let(::JsonPrimitive) ?: JsonNull)
                    HEARTBEAT_ACK -> acked = true
                    RECONNECT -> {
                        close(CloseReason(4000, "reconnect requested"))
                        break@loop
                    }
                    INVALID_SESSION -> {
                        if (payload["d"]?.jsonPrimitive?.booleanOrNull != true) clearSession()
                        delay(Random.nextLong(1_000, 5_000))
                        close(CloseReason(4000, "invalid session"))
                        break@loop
                    }
                }
            }
        } finally {
            heartbeat.cancel()
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveObject(): JsonObject? {
        for (frame in incoming) {
            if (frame is Frame.Text) return runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull()
        }
        return null
    }

    private suspend fun DefaultClientWebSocketSession.sendOp(op: Int, data: JsonElement) {
        send(Frame.Text(buildJsonObject { put("op", op); put("d", data) }.toString()))
    }

    companion object {
        /** DM 메시지만 받는다. DM 글 내용은 Message Content 권한 없이도 온다 */
        const val DIRECT_MESSAGES = 1 shl 12

        private const val DISPATCH = 0
        private const val HEARTBEAT = 1
        private const val IDENTIFY = 2
        private const val RESUME = 6
        private const val RECONNECT = 7
        private const val INVALID_SESSION = 9
        private const val HELLO = 10
        private const val HEARTBEAT_ACK = 11

        /** 다시 붙어도 소용없는 종료 코드: 토큰 거부, 샤드·버전·intent 오류 */
        private val FATAL = setOf(4004, 4010, 4011, 4012, 4013, 4014)
    }
}
