package dev.vesta.channels.telegram

import dev.vesta.channels.MessageSplitter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay

private val log = KotlinLogging.logger {}

/**
 * 생성 중인 답변을 메시지 수정으로 보여 준다 (PROJECT.md 6.5 스트리밍·긴 메시지).
 * 생성 중에는 서식 없는 글자로 [intervalMillis]마다 고치고, 길어지면 다음 메시지로 넘어간다.
 * 끝나면 각 조각을 HTML로 다시 그리고 마지막 조각에 출처를 붙인다.
 */
internal class LiveReply(
    private val api: TelegramApi,
    private val chatId: Long,
    private val intervalMillis: Long,
    private val nowMillis: () -> Long,
) {
    private val text = StringBuilder()
    private var status: String? = null
    private val messageIds = mutableListOf<Long>()
    private val shown = mutableListOf<String>()
    private var nextEdit = Long.MIN_VALUE

    /** false면 생성 중 답변 글자를 보여 주지 않는다 (상태만) */
    var streaming = true
    val answer: String get() = text.toString()

    suspend fun start(placeholder: String) {
        messageIds += api.sendMessage(chatId, placeholder).messageId
        shown += placeholder
        nextEdit = nowMillis() + intervalMillis
    }

    suspend fun status(value: String) {
        status = value.take(150)
        refresh(force = false)
    }

    suspend fun append(token: String) {
        text.append(token)
        refresh(force = false)
    }

    /** 승인 요청처럼 사용자가 지금까지의 내용을 봐야 할 때 */
    suspend fun flush() = refresh(force = true)

    private suspend fun refresh(force: Boolean) {
        if (!force && nowMillis() < nextEdit) return
        nextEdit = nowMillis() + intervalMillis
        val parts = MessageSplitter.split(if (streaming) answer else "", PART_LIMIT).ifEmpty { listOf("") }.toMutableList()
        parts[parts.lastIndex] = (parts.last() + "\n\n… " + (status ?: "작성 중")).trim()
        parts.forEachIndexed { i, part -> show(i, part, html = false, final = false) }
    }

    /** 최종 답변 [body](Markdown)와 출처 등 [footerHtml]을 보낸다. [body]가 비면 [footerHtml]만 */
    suspend fun finish(body: String, footerHtml: String?) {
        val parts = MessageSplitter.split(body, PART_LIMIT)
        val rendered = parts.map { TelegramHtml.render(it) }.toMutableList()
        if (footerHtml != null) {
            if (parts.isNotEmpty() && parts.last().length + footerHtml.length + 2 <= MESSAGE_LIMIT) rendered[rendered.lastIndex] = rendered.last() + "\n\n" + footerHtml
            else rendered += footerHtml
        }
        if (rendered.isEmpty() && messageIds.isNotEmpty()) rendered += "…"
        rendered.forEachIndexed { i, html ->
            if (!show(i, html, html = true, final = true)) {
                // HTML로 못 보내면(예상 못 한 태그 오류) 원문 글자로
                show(i, parts.getOrElse(i) { html.replace(Regex("<[^>]+>"), "") }, html = false, final = true)
            }
        }
        // 생성 중보다 조각이 줄었으면 남은 메시지를 비운다
        for (i in rendered.size until messageIds.size) show(i, "↑", html = false, final = true)
    }

    /** 성공하면 true. 생성 중 수정은 실패해도 넘어간다 */
    private suspend fun show(index: Int, content: String, html: Boolean, final: Boolean): Boolean {
        if (index < shown.size && shown[index] == content) return true
        repeat(3) {
            try {
                if (index < messageIds.size) api.editMessage(chatId, messageIds[index], content, html)
                else messageIds += api.sendMessage(chatId, content, html).messageId
                if (index < shown.size) shown[index] = content else shown += content
                return true
            } catch (e: TelegramException) {
                when {
                    e.notModified -> { if (index < shown.size) shown[index] = content; return true }
                    e.retryAfterSeconds != null -> {
                        nextEdit = nowMillis() + e.retryAfterSeconds * 1000L
                        if (!final) return false
                        delay(e.retryAfterSeconds * 1000L)
                    }
                    else -> { log.warn { "Telegram 메시지 표시 실패: ${e.message}" }; return false }
                }
            } catch (e: java.io.IOException) {
                log.warn { "Telegram 메시지 표시 실패: ${e.message}" }
                if (!final) return false
                delay(1_000)
            }
        }
        return false
    }

    companion object {
        /** Telegram 한도 4096자에서 상태 줄·출처 자리를 남긴다 */
        const val PART_LIMIT = 3_500
        const val MESSAGE_LIMIT = 4_096
    }
}
