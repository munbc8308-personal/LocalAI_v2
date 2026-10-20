package dev.vesta.channels.slack

import dev.vesta.channels.ButtonReply
import dev.vesta.channels.ChannelBot
import dev.vesta.channels.ChannelFiles
import dev.vesta.channels.Inbound
import dev.vesta.channels.InboundFile
import dev.vesta.core.channel.ChannelBindingStore
import dev.vesta.core.config.SlackConfig
import dev.vesta.core.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Slack 채널 어댑터 (PROJECT.md 6.4~6.6, I-06, M11).
 * Socket Mode로 앱 DM(메시지 탭)과 버튼 누름을 받아 [ChannelBot]에 넘긴다. 채널 글은 받지 않는다.
 *
 * 상태(대화방별 작업·승인)는 한 스레드에서만 고친다: [run]은 단일 스레드 디스패처에서 돈다.
 */
class SlackBot(
    private val api: SlackApi,
    private val events: SlackEvents,
    conversation: ConversationService,
    bindings: ChannelBindingStore,
    files: ChannelFiles,
    config: SlackConfig,
    status: suspend () -> String = { "정상" },
    clock: Clock = Clock.systemUTC(),
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val bot = ChannelBot(SlackSurface(api), conversation, bindings, files, config, status, clock, nowMillis)
    internal var selfId: String? = null

    /** 끝날 때까지 Socket Mode 연결을 유지한다. 토큰이 거부되면 멈춘다 */
    suspend fun run() = withContext(Dispatchers.Default.limitedParallelism(1)) {
        coroutineScope {
            val me = connect() ?: return@coroutineScope
            selfId = me.userId
            log.info { "Slack 봇 시작: ${me.user} (${me.team})" }
            events.run { type, payload -> handle(type, payload, this) }
        }
    }

    private suspend fun connect(): SlackAuth? {
        var wait = 2_000L
        while (true) {
            try {
                return api.authTest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SlackException) {
                if (e.fatal) { log.error { "Slack이 봇 토큰을 거부했습니다 (${e.error}). vesta slack token 으로 다시 저장하세요" }; return null }
                log.warn { "Slack 연결 실패: ${e.message}" }
            } catch (e: Exception) {
                log.warn { "Slack 연결 실패: ${e.message}" }
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000)
        }
    }

    /** Socket Mode 봉투 하나. 답변 생성처럼 오래 걸리는 일은 [scope]에 띄운다 */
    internal suspend fun handle(type: String, payload: JsonObject, scope: CoroutineScope) {
        when (type) {
            "events_api" -> (payload["event"] as? JsonObject)?.let { onEvent(it, scope) }
            "interactive" -> when (payload.string("type")) {
                "block_actions" -> onAction(payload, scope)
                "view_submission" -> onViewSubmission(payload, scope)
            }
        }
    }

    private suspend fun onEvent(event: JsonObject, scope: CoroutineScope) {
        if (event.string("type") != "message") return
        // 봇이 보낸 글, 고침·지움 같은 알림은 받지 않는다 (파일 올림만 예외)
        val subtype = event.string("subtype")
        if (event.string("bot_id") != null || (subtype != null && subtype != "file_share")) return
        val user = event.string("user") ?: return
        if (user == selfId) return
        val file = (event["files"] as? JsonArray)?.firstOrNull() as? JsonObject
        bot.handle(
            Inbound.Message(
                userId = user,
                userName = null,
                chat = event.string("channel") ?: return,
                text = event.string("text")?.let(SlackMrkdwn::unescape)?.ifBlank { null },
                direct = event.string("channel_type") == "im",
                sentAt = event.string("ts")?.substringBefore('.')?.toLongOrNull()?.let(Instant::ofEpochSecond),
                file = file?.let { f ->
                    val url = f.string("url_private_download") ?: f.string("url_private")
                    InboundFile(f.string("name") ?: "file", f["size"]?.jsonPrimitive?.longOrNull) { max ->
                        api.download(url ?: throw IOException("파일 주소 없음"), max)
                    }
                },
            ),
            scope,
        )
    }

    private suspend fun onAction(payload: JsonObject, scope: CoroutineScope) {
        val user = payload["user"] as? JsonObject ?: return
        val userId = user.string("id") ?: return
        val action = (payload["actions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val channel = (payload["channel"] as? JsonObject)?.string("id") ?: (payload["container"] as? JsonObject)?.string("channel_id")
        val data = action.string("value") ?: action.string("action_id") ?: return
        val reply = ActionReply(channel, userId, payload.string("trigger_id"))
        bot.handle(Inbound.Button(userId, user.string("username"), channel, data, reply), scope)
    }

    private suspend fun onViewSubmission(payload: JsonObject, scope: CoroutineScope) {
        val view = payload["view"] as? JsonObject ?: return
        if (view.string("callback_id") != EDIT_CALLBACK) return
        val user = payload["user"] as? JsonObject ?: return
        val requestId = view.string("private_metadata") ?: return
        val value = runCatching {
            view["state"]!!.jsonObject["values"]!!.jsonObject[EDIT_BLOCK]!!.jsonObject[EDIT_FIELD]!!.jsonObject.string("value")
        }.getOrNull() ?: return
        bot.handle(Inbound.EditSubmitted(user.string("id") ?: return, user.string("username"), requestId, value), scope)
    }

    /** 확인(ack)은 [SlackSocket]이 이미 보냈다. 여기서는 안내와 입력 창만 */
    private inner class ActionReply(private val channel: String?, private val user: String, private val triggerId: String?) : ButtonReply {
        override suspend fun ack(notice: String?, minor: Boolean) {
            val target = channel
            if (notice == null || minor || target == null) return
            runCatching { api.postEphemeral(target, user, notice) }.onFailure { log.warn { "Slack 안내 실패: ${it.message}" } }
        }

        override suspend fun openEditor(requestId: String, current: String): Boolean {
            // 잘린 명령을 고쳐 승인하는 일이 없게, 다 담기지 않으면 메시지로 받는다
            val trigger = triggerId
            if (trigger == null || current.length > EDIT_MAX) return false
            val view = buildJsonObject {
                put("type", "modal")
                put("callback_id", EDIT_CALLBACK)
                put("private_metadata", requestId)
                putJsonObject("title") { put("type", "plain_text"); put("text", "명령 수정") }
                putJsonObject("submit") { put("type", "plain_text"); put("text", "수정해서 승인") }
                putJsonObject("close") { put("type", "plain_text"); put("text", "취소") }
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input")
                        put("block_id", EDIT_BLOCK)
                        putJsonObject("label") { put("type", "plain_text"); put("text", "실행할 명령") }
                        putJsonObject("element") {
                            put("type", "plain_text_input")
                            put("action_id", EDIT_FIELD)
                            put("multiline", true)
                            put("initial_value", current)
                            put("max_length", EDIT_MAX)
                        }
                    })
                })
            }
            return try {
                api.openView(trigger, view)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Slack 입력 창 열기 실패: ${e.message}" }
                false
            }
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val EDIT_CALLBACK = "vesta_edit"
        const val EDIT_BLOCK = "cmd"
        const val EDIT_FIELD = "v"
        /** plain_text_input 한도 */
        const val EDIT_MAX = 3_000
    }
}
