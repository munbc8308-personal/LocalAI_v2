package dev.vesta.channels.slack

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/** 받은 Socket Mode 봉투를 넘겨주는 쪽 (시험에서는 가짜로 바꾼다) */
fun interface SlackEvents {
    /** 끝날 때까지 봉투를 [onEnvelope]로 넘긴다 (type: events_api, interactive …). 토큰이 거부되면 돌아온다 */
    suspend fun run(onEnvelope: suspend (type: String, payload: JsonObject) -> Unit)
}

/**
 * Slack Socket Mode (PROJECT.md 6.4: 공개 주소 없이 WebSocket으로 받는다).
 * 봉투를 받으면 바로 확인(ack)을 보낸 뒤 처리한다: Slack은 3초 안에 확인이 없으면 같은 이벤트를 다시 보낸다.
 * Slack이 연결을 바꾸라고 하면(disconnect) 새 주소로 다시 붙는다.
 */
class SlackSocket(
    private val api: SlackApi,
    private val http: HttpClient,
) : SlackEvents {
    private val json = Json { ignoreUnknownKeys = true }
    /** 다시 보낸 이벤트를 두 번 처리하지 않게 최근 event_id를 기억한다 */
    private val seen = LinkedHashSet<String>()

    override suspend fun run(onEnvelope: suspend (type: String, payload: JsonObject) -> Unit) {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            var ready = false
            try {
                val url = api.openConnection()
                http.webSocket(url) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                        val type = message["type"]?.jsonPrimitive?.contentOrNull ?: continue
                        when (type) {
                            "hello" -> { ready = true; log.info { "Slack Socket Mode 연결됨" } }
                            "disconnect" -> {
                                log.info { "Slack이 연결을 바꾸라고 함: ${message["reason"]?.jsonPrimitive?.contentOrNull}" }
                                break
                            }
                            else -> {
                                val envelope = message["envelope_id"]?.jsonPrimitive?.contentOrNull ?: continue
                                send(Frame.Text(buildJsonObject { put("envelope_id", envelope) }.toString()))
                                val payload = message["payload"] as? JsonObject ?: continue
                                if (duplicate(payload)) continue
                                try {
                                    onEnvelope(type, payload)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    log.error(e) { "Slack 이벤트 처리 실패 ($type)" }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 우리가 멈춘 게 아니라 연결 쪽 작업이 취소된 것이면(끊김) 다시 붙는다
                currentCoroutineContext().ensureActive()
                log.warn { "Slack 연결이 끊겼습니다" }
            } catch (e: SlackException) {
                if (e.fatal) {
                    log.error { "Slack이 토큰을 거부했습니다 (${e.error}). vesta slack token 으로 다시 저장하세요" }
                    return
                }
                log.warn { "Slack 연결 실패: ${e.message}" }
            } catch (e: Exception) {
                log.warn { "Slack 연결 실패: ${e::class.simpleName}: ${e.message}" }
            }
            if (ready) backoff = 1_000L
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    private fun duplicate(payload: JsonObject): Boolean {
        val id = payload["event_id"]?.jsonPrimitive?.contentOrNull ?: return false
        if (!seen.add(id)) return true
        while (seen.size > 500) seen.remove(seen.first())
        return false
    }
}
