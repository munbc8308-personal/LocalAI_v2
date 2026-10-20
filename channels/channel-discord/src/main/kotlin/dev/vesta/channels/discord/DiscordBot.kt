package dev.vesta.channels.discord

import dev.vesta.channels.ButtonReply
import dev.vesta.channels.ChannelBot
import dev.vesta.channels.ChannelFiles
import dev.vesta.channels.FileKind
import dev.vesta.channels.Inbound
import dev.vesta.channels.InboundFile
import dev.vesta.core.channel.ChannelBindingStore
import dev.vesta.core.config.DiscordConfig
import dev.vesta.core.config.MediaConfig
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.media.SpeechToText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Discord 채널 어댑터 (PROJECT.md 6.4~6.6, I-06, M11).
 * Gateway로 DM과 버튼 누름을 받아 [ChannelBot]에 넘긴다. 서버(길드) 대화방 글은 받지 않는다.
 *
 * 상태(대화방별 작업·승인)는 한 스레드에서만 고친다: [run]은 단일 스레드 디스패처에서 돈다.
 */
class DiscordBot(
    private val api: DiscordApi,
    private val events: DiscordEvents,
    conversation: ConversationService,
    bindings: ChannelBindingStore,
    files: ChannelFiles,
    config: DiscordConfig,
    status: suspend () -> String = { "정상" },
    clock: Clock = Clock.systemUTC(),
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    speechToText: SpeechToText? = null,
    mediaLimits: MediaConfig = MediaConfig(),
) {
    // 음성 답변은 보내지 않는다 (Discord 음성 메시지 형식은 따로 만들어야 함)
    private val bot = ChannelBot(DiscordSurface(api), conversation, bindings, files, config, status, clock, nowMillis, speechToText, null, mediaLimits)
    private val json = Json { ignoreUnknownKeys = true }
    private var selfId: String? = null

    /** 끝날 때까지 Gateway 연결을 유지한다. 토큰이 거부되면 멈춘다 */
    suspend fun run() = withContext(Dispatchers.Default.limitedParallelism(1)) {
        coroutineScope {
            events.run { type, data -> dispatch(type, data, this) }
        }
    }

    /** Gateway 이벤트 하나. 답변 생성처럼 오래 걸리는 일은 [scope]에 띄운다 */
    internal suspend fun dispatch(type: String, data: JsonObject, scope: CoroutineScope) {
        when (type) {
            "READY" -> {
                val me = data["user"]?.let { json.decodeFromJsonElement(DcUser.serializer(), it) }
                selfId = me?.id
                log.info { "Discord 봇 시작: ${me?.username}" }
            }
            "MESSAGE_CREATE" -> onMessage(json.decodeFromJsonElement(DcMessage.serializer(), data), scope)
            "INTERACTION_CREATE" -> onInteraction(json.decodeFromJsonElement(DcInteraction.serializer(), data), scope)
        }
    }

    private suspend fun onMessage(message: DcMessage, scope: CoroutineScope) {
        val author = message.author ?: return
        // 봇 자신(과 다른 봇)이 보낸 글
        if (author.bot || author.id == selfId) return
        val attachment = message.attachments.firstOrNull()
        bot.handle(
            Inbound.Message(
                userId = author.id,
                userName = author.username,
                chat = message.channelId,
                text = message.content.ifEmpty { null },
                direct = message.guildId == null,
                sentAt = message.timestamp?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                file = attachment?.let { a -> InboundFile(a.filename, a.size, kindOf(a.contentType)) { max -> api.download(a.url, max) } },
            ),
            scope,
        )
    }

    private suspend fun onInteraction(interaction: DcInteraction, scope: CoroutineScope) {
        val user = interaction.user ?: interaction.member?.user ?: return
        val customId = interaction.data?.customId.orEmpty()
        when (interaction.type) {
            BUTTON -> {
                val reply = InteractionReply(interaction)
                try {
                    bot.handle(Inbound.Button(user.id, user.username, interaction.channelId, customId, reply), scope)
                } finally {
                    // 3초 안에 답하지 않으면 Discord가 "상호 작용 실패"를 띄운다
                    reply.ensureAcked()
                }
            }
            MODAL_SUBMIT -> {
                if (!customId.startsWith(EDIT_PREFIX)) return
                val value = interaction.data?.components.orEmpty()
                    .flatMap { row -> row.components + listOfNotNull(row.component) }
                    .firstOrNull { it.customId == EDIT_FIELD }?.value ?: return
                runCatching { api.respond(interaction.id, interaction.token, deferredUpdate()) }
                    .onFailure { log.warn { "Discord 입력 창 응답 실패: ${it.message}" } }
                bot.handle(Inbound.EditSubmitted(user.id, user.username, customId.removePrefix(EDIT_PREFIX), value), scope)
            }
        }
    }

    private inner class InteractionReply(private val interaction: DcInteraction) : ButtonReply {
        private var answered = false

        override suspend fun ack(notice: String?, minor: Boolean) {
            if (answered) return
            answered = true
            val response = if (notice != null && !minor) buildJsonObject {
                put("type", CHANNEL_MESSAGE)
                putJsonObject("data") { put("content", notice); put("flags", EPHEMERAL) }
            } else deferredUpdate()
            runCatching { api.respond(interaction.id, interaction.token, response) }
                .onFailure { log.warn { "Discord 버튼 응답 실패: ${it.message}" } }
        }

        /** 승인 수정: 지금 명령을 채운 입력 창을 띄운다 */
        override suspend fun openEditor(requestId: String, current: String): Boolean {
            // 잘린 명령을 고쳐 승인하는 일이 없게, 다 담기지 않으면 메시지로 받는다
            if (answered || current.length > EDIT_MAX) return false
            val modal = buildJsonObject {
                put("type", MODAL)
                putJsonObject("data") {
                    put("custom_id", EDIT_PREFIX + requestId)
                    put("title", "명령 수정")
                    put("components", buildJsonArray {
                        add(buildJsonObject {
                            put("type", 1)
                            put("components", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", 4)
                                    put("custom_id", EDIT_FIELD)
                                    put("label", "실행할 명령")
                                    put("style", 2)
                                    put("value", current)
                                    put("max_length", EDIT_MAX)
                                    put("required", true)
                                })
                            })
                        })
                    })
                }
            }
            return try {
                api.respond(interaction.id, interaction.token, modal)
                answered = true
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Discord 입력 창 열기 실패: ${e.message}" }
                false
            }
        }

        suspend fun ensureAcked() {
            if (!answered) ack()
        }
    }

    private fun deferredUpdate() = buildJsonObject { put("type", DEFERRED_UPDATE) }

    private companion object {
        const val BUTTON = 3
        const val MODAL_SUBMIT = 5
        const val CHANNEL_MESSAGE = 4
        const val DEFERRED_UPDATE = 6
        const val MODAL = 9
        const val EPHEMERAL = 1 shl 6
        const val EDIT_PREFIX = "ed:"
        const val EDIT_FIELD = "command"
        /** 승인 카드에 보여 줄 수 있는 명령 길이와 같게 */
        const val EDIT_MAX = 1_200
    }
}

/** 플랫폼이 알려 준 형식으로 음성·이미지를 가린다 */
private fun kindOf(mime: String?) = when {
    mime == null -> FileKind.DOCUMENT
    mime.startsWith("audio/") -> FileKind.VOICE
    mime.startsWith("image/") -> FileKind.IMAGE
    else -> FileKind.DOCUMENT
}
