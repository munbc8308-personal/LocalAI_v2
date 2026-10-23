package dev.vesta.channels.slack

import dev.vesta.channels.ApprovalCard
import dev.vesta.channels.ButtonStyle
import dev.vesta.channels.ChannelButton
import dev.vesta.channels.ChatSurface
import dev.vesta.channels.ReplyFooter
import dev.vesta.channels.slack.SlackMrkdwn.escape
import dev.vesta.core.ChannelType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Slack 메시지 표시: mrkdwn, Block Kit 버튼, 수정은 입력 창(모달)으로 받는다 */
internal class SlackSurface(private val api: SlackApi) : ChatSurface {
    override val channel = ChannelType.SLACK
    override val name = "Slack"
    override val key = "slack"
    /** Slack 앱은 `/글자`를 앱 명령으로 가로채므로 `!`로 쓴다 */
    override val commandPrefix = '!'
    /** 블록 하나 3000자 (PROJECT.md 6.5). 이스케이프로 늘어날 자리를 남긴다 */
    override val partLimit = 2_800
    override val messageLimit = 3_900
    override val approvalBodyLimit = 2_800

    override suspend fun sendText(chat: String, text: String) = api.postMessage(chat, escape(text))

    override suspend fun editText(chat: String, messageId: String, text: String) = api.update(chat, messageId, escape(text), JsonArray(emptyList()))

    override suspend fun post(chat: String, content: String, formatted: Boolean) = api.postMessage(chat, if (formatted) content else escape(content))

    override suspend fun edit(chat: String, messageId: String, content: String, formatted: Boolean) =
        api.update(chat, messageId, if (formatted) content else escape(content))

    override fun render(markdown: String) = SlackMrkdwn.render(markdown)

    override fun renderFooter(footer: ReplyFooter): String {
        val lines = mutableListOf<String>()
        if (footer.sources.isNotEmpty()) {
            lines += "*출처*"
            footer.sources.forEach { s -> lines += "[${s.number}] " + (s.url?.let { SlackMrkdwn.link(s.label, it) } ?: escape(s.label)) }
        }
        if (footer.unsupported > 0) lines += "_근거로 확인되지 않은 문장 ${footer.unsupported}개_"
        footer.agent?.let { lines += "_${it}_" }
        return lines.joinToString("\n")
    }

    override suspend fun sendButtons(chat: String, text: String, rows: List<List<ChannelButton>>): String {
        val blocks = buildJsonArray {
            add(section(escape(text)))
            add(actions(rows.flatten()))
        }
        return api.postMessage(chat, escape(text), blocks)
    }

    override suspend fun sendApproval(chat: String, card: ApprovalCard, buttons: List<ChannelButton>): String {
        val blocks = JsonArray(cardBlocks(card) + actions(buttons))
        return api.postMessage(chat, "승인 필요: ${escape(card.tool)}", blocks)
    }

    override suspend fun closeApproval(chat: String, messageId: String, card: ApprovalCard, result: String, edited: String?) {
        val resultBlocks = listOf(section("*→ ${escape(result)}*")) + (edited?.let(::codeSections) ?: emptyList())
        api.update(chat, messageId, "승인 필요: ${escape(card.tool)} → ${escape(result)}", JsonArray(cardBlocks(card) + resultBlocks))
    }

    override suspend fun sendVideo(chat: String, mp4: ByteArray, caption: String?): Boolean {
        if (mp4.size > 100L * 1024 * 1024) return false
        api.uploadFile(chat, "vesta.mp4", mp4, "AI 생성" + (caption?.let { " · $it" } ?: ""))
        return true
    }

    override suspend fun sendImage(chat: String, png: ByteArray, caption: String?): Boolean {
        api.uploadFile(chat, "vesta.png", png, "AI 생성" + (caption?.let { " · $it" } ?: ""))
        return true
    }

    override suspend fun askEdit(chat: String, current: String) {
        api.postMessage(chat, "수정한 명령을 보내 주세요. 지금 명령:\n" + SlackMrkdwn.codeBlock(current))
    }

    private fun cardBlocks(card: ApprovalCard): List<JsonObject> = buildList {
        add(section("*승인 필요* · ${escape(card.tool)} (${card.risk})\n${escape(card.title.take(300))}"))
        addAll(codeSections(card.body))
        val notes = listOfNotNull(
            card.reason?.let { "이유: " + escape(it.take(300)) },
            card.warning?.let { "*주의*: " + escape(it.take(300)) },
            "_답하지 않으면 잠시 뒤 자동으로 거부됩니다._",
        )
        add(section(notes.joinToString("\n")))
    }

    /**
     * 실행할 내용 전체를 코드 블록으로. 블록 하나는 3000자까지라, 이스케이프로 길어지면 줄 단위로 여러 블록에 나눠 담는다
     * (승인 화면에서 명령을 자르지 않는다).
     */
    private fun codeSections(text: String): List<JsonObject> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.split('\n').flatMap { it.chunked(CODE_CHUNK).ifEmpty { listOf("") } }) {
            if (current.isNotEmpty() && SlackMrkdwn.codeBlock(current.toString() + "\n" + line).length > SECTION_LIMIT) {
                chunks += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        chunks += current.toString()
        return chunks.map { section(SlackMrkdwn.codeBlock(it)) }
    }

    private fun section(mrkdwn: String) = buildJsonObject {
        put("type", "section")
        putJsonObject("text") { put("type", "mrkdwn"); put("text", mrkdwn) }
    }

    private fun actions(buttons: List<ChannelButton>) = buildJsonObject {
        put("type", "actions")
        put("elements", buildJsonArray {
            buttons.take(25).forEach { b ->
                add(buildJsonObject {
                    put("type", "button")
                    putJsonObject("text") { put("type", "plain_text"); put("text", b.label.take(75)) }
                    put("action_id", b.data)
                    put("value", b.data)
                    when (b.style) {
                        ButtonStyle.PRIMARY -> put("style", "primary")
                        ButtonStyle.DANGER -> put("style", "danger")
                        ButtonStyle.NORMAL -> Unit
                    }
                })
            }
        })
    }

    private companion object {
        const val SECTION_LIMIT = 3_000
        /** 한 줄이 아주 길면 이 길이로 끊는다 (& → &amp; 로 다섯 배가 돼도 한 블록에 들어가게) */
        const val CODE_CHUNK = 580
    }
}
