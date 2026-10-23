package dev.vesta.channels

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

/**
 * 생성 중인 답변을 메시지 수정으로 보여 준다 (PROJECT.md 6.5 스트리밍·긴 메시지).
 * 생성 중에는 서식 없는 글자로 [intervalMillis]마다 고치고, 길어지면 다음 메시지로 넘어간다.
 * 끝나면 각 조각을 플랫폼 서식으로 다시 그리고 마지막 조각에 출처를 붙인다.
 *
 * [scope]가 있으면 생성 중 수정은 그 안의 별도 작업이 한다: 메신저 API 호출(텔레그램은 한 번에 0.3~1.4초)을
 * 생성이 기다리지 않는다. 기다리면 수정 횟수만큼 답변이 늦어진다 (M15 실기기 확인: 52토큰 답변이 66초).
 */
internal class LiveReply(
    private val surface: ChatSurface,
    private val chat: String,
    private val intervalMillis: Long,
    private val nowMillis: () -> Long,
    private val scope: CoroutineScope? = null,
) {
    private val text = StringBuilder()
    /** 메시지 표시는 한 번에 하나씩 (생성 중 수정 작업과 마무리가 겹치지 않게) */
    private val showing = Mutex()
    private var sender: Job? = null
    @Volatile private var dirty = false
    private var status: String? = null
    private val messageIds = mutableListOf<String>()
    /** 이 답에 쓴 메신저 메시지 ID (반응 → 피드백, L-20) */
    val ids: List<String> get() = messageIds.toList()
    private val shown = mutableListOf<String>()
    private var nextEdit = Long.MIN_VALUE

    /** false면 생성 중 답변 글자를 보여 주지 않는다 (상태만) */
    var streaming = true
    val answer: String get() = synchronized(text) { text.toString() }

    /** 메신저 API 호출에 쓴 시간·횟수 (느린 답변 진단용) */
    var apiMillis = 0L
        private set
    var apiCalls = 0
        private set

    suspend fun start(placeholder: String) {
        val started = nowMillis()
        messageIds += surface.post(chat, placeholder, formatted = false)
        apiMillis += nowMillis() - started
        apiCalls++
        shown += placeholder
        nextEdit = nowMillis() + intervalMillis
    }

    suspend fun status(value: String) {
        status = value.take(150)
        refresh(force = false)
    }

    suspend fun append(token: String) {
        synchronized(text) { text.append(token) }
        refresh(force = false)
    }

    /** 승인 요청처럼 사용자가 지금까지의 내용을 봐야 할 때 */
    suspend fun flush() = refresh(force = true)

    private suspend fun refresh(force: Boolean) {
        val background = scope
        if (force || background == null) {
            if (!force && nowMillis() < nextEdit) return
            stopSender()
            showing.withLock { render() }
            return
        }
        // 생성은 기다리지 않는다: 바뀌었다고만 표시하고, 수정 작업이 간격을 지켜 최신 내용을 보낸다
        dirty = true
        if (sender?.isActive == true) return
        sender = background.launch {
            while (dirty) {
                val wait = nextEdit - nowMillis()
                if (wait > 0) delay(wait)
                dirty = false
                showing.withLock { render() }
            }
        }
    }

    private suspend fun stopSender() {
        sender?.cancelAndJoin()
        sender = null
    }

    private suspend fun render() {
        nextEdit = nowMillis() + intervalMillis
        val parts = MessageSplitter.split(if (streaming) answer else "", surface.partLimit).ifEmpty { listOf("") }.toMutableList()
        parts[parts.lastIndex] = (parts.last() + "\n\n… " + (status ?: "작성 중")).trim()
        parts.forEachIndexed { i, part -> show(i, part, formatted = false, final = false) }
    }

    /** 최종 답변 [body](Markdown)와 [footer]를 보낸다. [body]가 비면 [footer]만 */
    suspend fun finish(body: String, footer: ReplyFooter?) {
        stopSender()
        showing.withLock { finishLocked(body, footer) }
    }

    private suspend fun finishLocked(body: String, footer: ReplyFooter?) {
        val parts = MessageSplitter.split(body, surface.partLimit)
        val rendered = parts.map { surface.render(it) }.toMutableList()
        val footerText = footer?.takeUnless { it.isEmpty }?.let(surface::renderFooter)
        if (footerText != null) {
            if (parts.isNotEmpty() && rendered.last().length + footerText.length + 2 <= surface.messageLimit) rendered[rendered.lastIndex] = rendered.last() + "\n\n" + footerText
            else rendered += footerText
        }
        if (rendered.isEmpty() && messageIds.isNotEmpty()) rendered += "…"
        rendered.forEachIndexed { i, content ->
            if (!show(i, content, formatted = true, final = true)) {
                // 서식 때문에 못 보내면(예상 못 한 태그 오류) 원문 글자로
                show(i, parts.getOrElse(i) { content.replace(Regex("<[^>]+>"), "") }, formatted = false, final = true)
            }
        }
        // 생성 중보다 조각이 줄었으면 남은 메시지를 비운다
        for (i in rendered.size until messageIds.size) show(i, "↑", formatted = false, final = true)
    }

    /** 성공하면 true. 생성 중 수정은 실패해도 넘어간다 */
    private suspend fun show(index: Int, content: String, formatted: Boolean, final: Boolean): Boolean {
        if (index < shown.size && shown[index] == content) return true
        repeat(3) {
            val started = nowMillis()
            try {
                apiCalls++
                if (index < messageIds.size) surface.edit(chat, messageIds[index], content, formatted)
                else messageIds += surface.post(chat, content, formatted)
                apiMillis += nowMillis() - started
                if (index < shown.size) shown[index] = content else shown += content
                return true
            } catch (e: ChannelApiException) {
                apiMillis += nowMillis() - started
                val wait = e.retryAfterMillis
                when {
                    e.notModified -> { if (index < shown.size) shown[index] = content; return true }
                    wait != null -> {
                        nextEdit = nowMillis() + wait
                        if (!final) return false
                        delay(wait)
                    }
                    else -> { log.warn { "${surface.name} 메시지 표시 실패: ${e.message}" }; return false }
                }
            } catch (e: java.io.IOException) {
                apiMillis += nowMillis() - started
                log.warn { "${surface.name} 메시지 표시 실패: ${e.message}" }
                if (!final) return false
                delay(1_000)
            }
        }
        return false
    }
}
