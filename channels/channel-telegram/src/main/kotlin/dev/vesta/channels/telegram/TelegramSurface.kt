package dev.vesta.channels.telegram

import dev.vesta.channels.ApprovalCard
import dev.vesta.channels.ChannelButton
import dev.vesta.channels.ChatSurface
import dev.vesta.channels.ReplyFooter
import dev.vesta.channels.telegram.TelegramHtml.escape
import dev.vesta.core.ChannelType

/** Telegram 메시지 표시: HTML 서식(parse_mode=HTML), 인라인 버튼, 수정은 답장(force reply)으로 받는다 */
internal class TelegramSurface(private val api: TelegramApi) : ChatSurface {
    override val channel = ChannelType.TELEGRAM
    override val name = "Telegram"
    override val key = "telegram"
    override val commandPrefix = '/'
    /** Telegram 한도 4096자에서 상태 줄·출처 자리를 남긴다 */
    override val partLimit = 3_500
    override val messageLimit = 4_096
    override val approvalBodyLimit = 3_000

    override suspend fun sendText(chat: String, text: String) = api.sendMessage(chat.toLong(), text).messageId.toString()

    override suspend fun editText(chat: String, messageId: String, text: String) = api.editMessage(chat.toLong(), messageId.toLong(), text)

    override suspend fun post(chat: String, content: String, formatted: Boolean) =
        api.sendMessage(chat.toLong(), content, html = formatted).messageId.toString()

    override suspend fun edit(chat: String, messageId: String, content: String, formatted: Boolean) =
        api.editMessage(chat.toLong(), messageId.toLong(), content, html = formatted)

    override fun render(markdown: String) = TelegramHtml.render(markdown)

    override fun renderFooter(footer: ReplyFooter): String {
        val lines = mutableListOf<String>()
        if (footer.sources.isNotEmpty()) {
            lines += "<b>출처</b>"
            footer.sources.forEach { s -> lines += "[${s.number}] " + (s.url?.let { TelegramHtml.link(s.label, it) } ?: escape(s.label)) }
        }
        if (footer.unsupported > 0) lines += "<i>근거로 확인되지 않은 문장 ${footer.unsupported}개</i>"
        footer.agent?.let { lines += "<i>$it</i>" }
        return lines.joinToString("\n")
    }

    override suspend fun sendButtons(chat: String, text: String, rows: List<List<ChannelButton>>) =
        api.sendMessage(chat.toLong(), text, keyboard = keyboard(rows)).messageId.toString()

    override suspend fun sendApproval(chat: String, card: ApprovalCard, buttons: List<ChannelButton>) =
        api.sendMessage(chat.toLong(), cardHtml(card), html = true, keyboard = keyboard(listOf(buttons))).messageId.toString()

    override suspend fun closeApproval(chat: String, messageId: String, card: ApprovalCard, result: String, edited: String?) {
        val label = escape(result) + (edited?.let { ": <code>${escape(it)}</code>" } ?: "")
        api.editMessage(chat.toLong(), messageId.toLong(), cardHtml(card) + "\n\n<b>→ $label</b>", html = true)
    }

    override val supportsVoice = true

    override suspend fun sendVoice(chat: String, ogg: ByteArray) = api.sendVoice(chat.toLong(), ogg)

    /** 봇 API 올리기 한도 50MB */
    override suspend fun sendVideo(chat: String, mp4: ByteArray, caption: String?): Boolean {
        if (mp4.size > 50L * 1024 * 1024) return false
        api.sendVideo(chat.toLong(), mp4, caption?.let { "AI 생성 · $it" } ?: "AI 생성")
        return true
    }

    override suspend fun sendImage(chat: String, png: ByteArray, caption: String?): Boolean {
        api.sendPhoto(chat.toLong(), png, caption?.let { "AI 생성 · $it" } ?: "AI 생성")
        return true
    }

    override suspend fun askEdit(chat: String, current: String) {
        api.sendMessage(chat.toLong(), "수정한 명령을 보내 주세요. 지금 명령:\n<pre>${escape(current)}</pre>", html = true, forceReply = true)
    }

    private fun keyboard(rows: List<List<ChannelButton>>) = TgKeyboard(rows.map { row -> row.map { TgButton(it.label, it.data) } })

    private fun cardHtml(card: ApprovalCard) = buildString {
        append("<b>승인 필요</b> · ").append(escape(card.tool)).append(" (").append(card.risk).append(")\n")
        append(escape(card.title.take(300))).append('\n')
        append("<pre>").append(escape(card.body)).append("</pre>")
        card.reason?.let { append("\n이유: ").append(escape(it.take(300))) }
        card.warning?.let { append("\n<b>주의</b>: ").append(escape(it.take(300))) }
        append("\n<i>답하지 않으면 잠시 뒤 자동으로 거부됩니다.</i>")
    }
}
