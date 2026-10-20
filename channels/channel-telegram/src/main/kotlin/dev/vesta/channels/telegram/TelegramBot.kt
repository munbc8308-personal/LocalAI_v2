package dev.vesta.channels.telegram

import dev.vesta.channels.ButtonReply
import dev.vesta.channels.ChannelBot
import dev.vesta.channels.ChannelFiles
import dev.vesta.channels.Inbound
import dev.vesta.channels.InboundFile
import dev.vesta.core.channel.ChannelBindingStore
import dev.vesta.core.config.TelegramConfig
import dev.vesta.core.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Telegram 채널 어댑터 (PROJECT.md 6.4~6.6, I-05, M10).
 * long polling으로 받으므로 공개 주소가 필요 없다. 받은 업데이트는 [ChannelBot]에 넘긴다.
 *
 * 상태(대화방별 작업·승인)는 한 스레드에서만 고친다: [run]은 단일 스레드 디스패처에서 돈다.
 */
class TelegramBot(
    private val api: TelegramApi,
    conversation: ConversationService,
    bindings: ChannelBindingStore,
    files: ChannelFiles,
    config: TelegramConfig,
    status: suspend () -> String = { "정상" },
    clock: Clock = Clock.systemUTC(),
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val bot = ChannelBot(TelegramSurface(api), conversation, bindings, files, config, status, clock, nowMillis)

    /** 끝날 때까지 long polling. 토큰이 거부되면 멈춘다 */
    suspend fun run() = withContext(Dispatchers.Default.limitedParallelism(1)) {
        coroutineScope {
            val me = connect() ?: return@coroutineScope
            log.info { "Telegram 봇 시작: @${me.username}" }
            runCatching { api.setCommands(ChannelBot.COMMANDS) }.onFailure { log.warn { "명령 목록 등록 실패: ${it.message}" } }
            var offset: Long? = null
            var backoff = 1_000L
            while (isActive) {
                val updates = try {
                    api.getUpdates(offset, POLL_SECONDS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TelegramException) {
                    if (e.code == 401) { log.error { "Telegram 토큰이 거부됐습니다. vesta telegram token 으로 다시 저장하세요" }; return@coroutineScope }
                    // 409: 다른 곳에서 같은 봇으로 getUpdates 중이거나 webhook이 걸려 있음
                    log.warn { "Telegram 받기 실패: ${e.message}" }
                    delay(e.retryAfterMillis ?: if (e.code == 409) 30_000 else backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                    continue
                } catch (e: Exception) {
                    log.warn { "Telegram 연결 실패: ${e.message}" }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                    continue
                }
                backoff = 1_000L
                for (update in updates) {
                    offset = update.updateId + 1
                    try {
                        handle(update, this)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(e) { "Telegram 메시지 처리 실패 (update ${update.updateId})" }
                    }
                }
            }
        }
    }

    private suspend fun connect(): TgUser? {
        var wait = 2_000L
        while (true) {
            try {
                return api.getMe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramException) {
                if (e.code == 401) { log.error { "Telegram 토큰이 거부됐습니다. vesta telegram token 으로 다시 저장하세요" }; return null }
                log.warn { "Telegram 연결 실패: ${e.message}" }
            } catch (e: Exception) {
                log.warn { "Telegram 연결 실패: ${e.message}" }
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000)
        }
    }

    /** 업데이트 하나 처리. 답변 생성처럼 오래 걸리는 일은 [scope]에 띄운다 */
    internal suspend fun handle(update: TgUpdate, scope: CoroutineScope) {
        update.message?.let { message ->
            val from = message.from ?: return@let
            val document = message.document
            bot.handle(
                Inbound.Message(
                    userId = from.id.toString(),
                    userName = from.username,
                    chat = message.chat.id.toString(),
                    text = message.text ?: message.caption,
                    direct = message.chat.type == "private",
                    sentAt = message.date.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
                    file = document?.let { d -> InboundFile(d.fileName ?: "file", d.fileSize) { max -> api.download(d.fileId, max) } },
                ),
                scope,
            )
        }
        update.callbackQuery?.let { query ->
            val reply = object : ButtonReply {
                override suspend fun ack(notice: String?, minor: Boolean) = api.answerCallback(query.id, notice)
            }
            bot.handle(Inbound.Button(query.from.id.toString(), query.from.username, query.message?.chat?.id?.toString(), query.data.orEmpty(), reply), scope)
        }
    }

    companion object {
        const val POLL_SECONDS = 50
    }
}
