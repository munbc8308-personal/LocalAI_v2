package dev.vesta.channels.discord

import dev.vesta.channels.ApprovalCard
import dev.vesta.channels.ButtonStyle
import dev.vesta.channels.ChannelButton
import dev.vesta.channels.ChatSurface
import dev.vesta.channels.ReplyFooter
import dev.vesta.channels.discord.DiscordMarkdown.codeBlock
import dev.vesta.channels.discord.DiscordMarkdown.escape
import dev.vesta.core.ChannelType

/** Discord 메시지 표시: Markdown 그대로, 버튼 컴포넌트, 수정은 입력 창(모달)으로 받는다 */
internal class DiscordSurface(private val api: DiscordApi) : ChatSurface {
    override val channel = ChannelType.DISCORD
    override val name = "Discord"
    override val key = "discord"
    /** 앱 명령으로 등록하지 않은 `/글자`는 Discord 앱이 막을 수 있어 `!`로 안내한다 (`/`도 받는다) */
    override val commandPrefix = '!'
    /** Discord 한도 2000자에서 상태 줄·출처 자리를 남긴다 */
    override val partLimit = 1_800
    override val messageLimit = MESSAGE_LIMIT
    override val approvalBodyLimit = 1_200

    override suspend fun sendText(chat: String, text: String) = api.createMessage(chat, escape(text).take(MESSAGE_LIMIT)).id

    override suspend fun editText(chat: String, messageId: String, text: String) = api.editMessage(chat, messageId, escape(text).take(MESSAGE_LIMIT))

    override suspend fun post(chat: String, content: String, formatted: Boolean) = api.createMessage(chat, content.take(MESSAGE_LIMIT)).id

    override suspend fun edit(chat: String, messageId: String, content: String, formatted: Boolean) =
        api.editMessage(chat, messageId, content.take(MESSAGE_LIMIT))

    override fun render(markdown: String) = DiscordMarkdown.render(markdown)

    override fun renderFooter(footer: ReplyFooter): String {
        val lines = mutableListOf<String>()
        if (footer.sources.isNotEmpty()) {
            lines += "**출처**"
            footer.sources.forEach { s ->
                val url = s.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                lines += "[${s.number}] " + if (url != null) "[${s.label.replace('[', '(').replace(']', ')')}](<${url.replace(">", "%3E")}>)" else escape(s.label)
            }
        }
        if (footer.unsupported > 0) lines += "*근거로 확인되지 않은 문장 ${footer.unsupported}개*"
        footer.agent?.let { lines += "*$it*" }
        return lines.joinToString("\n")
    }

    /** Discord는 한 줄에 버튼 5개, 5줄까지라 버튼을 다시 나눠 담는다 */
    override suspend fun sendButtons(chat: String, text: String, rows: List<List<ChannelButton>>) =
        api.createMessage(chat, escape(text).take(MESSAGE_LIMIT), rows.flatten().take(25).map(::button).chunked(5)).id

    override suspend fun sendApproval(chat: String, card: ApprovalCard, buttons: List<ChannelButton>) =
        api.createMessage(chat, cardText(card), listOf(buttons.map(::button))).id

    override suspend fun closeApproval(chat: String, messageId: String, card: ApprovalCard, result: String, edited: String?) {
        val tail = "\n\n**→ ${escape(result)}**" + (edited?.let { "\n" + codeBlock(it) } ?: "")
        // 수정한 명령까지 넣으면 길이를 넘을 때는 원래 명령을 줄인다
        val content = (cardText(card) + tail).takeIf { it.length <= MESSAGE_LIMIT } ?: (cardText(card.copy(body = "(생략)")) + tail)
        api.editMessage(chat, messageId, content.take(MESSAGE_LIMIT))
    }

    override suspend fun sendImage(chat: String, png: ByteArray, caption: String?): Boolean {
        api.createMessageWithFile(chat, "AI 생성" + (caption?.let { " · " + escape(it.take(1_500)) } ?: ""), "vesta.png", png, "image/png")
        return true
    }

    /** 음성으로 물으면 음성 메시지로 답한다 (`!voice`로 켬, M18) */
    override val supportsVoice = true

    override suspend fun sendVoice(chat: String, ogg: ByteArray) {
        val seconds = OggOpus.durationSeconds(ogg).coerceAtLeast(0.1)
        api.createVoiceMessage(chat, ogg, seconds, OggOpus.waveform(seconds))
    }

    override suspend fun askEdit(chat: String, current: String) {
        api.createMessage(chat, "수정한 명령을 보내 주세요. 지금 명령:\n" + codeBlock(current))
    }

    private fun button(b: ChannelButton) = DcButton(
        b.label,
        b.data,
        when (b.style) {
            ButtonStyle.PRIMARY -> 1
            ButtonStyle.DANGER -> 4
            ButtonStyle.NORMAL -> 2
        },
    )

    private fun cardText(card: ApprovalCard) = buildString {
        append("**승인 필요** · ").append(escape(card.tool)).append(" (").append(card.risk).append(")\n")
        append(escape(card.title.take(150))).append('\n')
        append(codeBlock(card.body))
        card.reason?.let { append("\n이유: ").append(escape(it.take(200))) }
        card.warning?.let { append("\n**주의**: ").append(escape(it.take(200))) }
        append("\n*답하지 않으면 잠시 뒤 자동으로 거부됩니다.*")
    }

    companion object {
        const val MESSAGE_LIMIT = 2_000
    }
}
