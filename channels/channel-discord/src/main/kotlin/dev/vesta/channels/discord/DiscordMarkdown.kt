package dev.vesta.channels.discord

import dev.vesta.channels.MarkdownTable

/**
 * 답변 Markdown → Discord Markdown. Discord는 Markdown을 그대로 그리므로 못 그리는 것만 바꾼다:
 * 표는 고정폭 코드 블록으로, `####` 이하 제목은 굵게. 멘션은 메시지마다 꺼 두므로(allowed_mentions) 글자로만 보인다.
 */
object DiscordMarkdown {
    private val deepHeading = Regex("^#{4,6}\\s+(.*)$")
    private val special = Regex("([\\\\*_~`|>\\[\\]()#-])")

    fun render(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<String>()
        var inCode = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> { inCode = !inCode; out += line }
                inCode -> out += line
                MarkdownTable.startsAt(lines, i) -> {
                    val (table, next) = MarkdownTable.read(lines, i)
                    out += codeBlock(table)
                    i = next
                    continue
                }
                deepHeading.matches(trimmed) -> out += "**" + deepHeading.find(trimmed)!!.groupValues[1] + "**"
                // 빈 줄이 여러 개면 하나로
                line.isBlank() -> if (out.lastOrNull()?.isBlank() == false) out += ""
                else -> out += line
            }
            i++
        }
        return out.joinToString("\n").trim()
    }

    /** 서식 없이 글자 그대로 보이게 */
    fun escape(text: String) = text.replace(special, "\\\\$1")

    /** 안의 ``` 때문에 블록이 일찍 닫히지 않게 끊어 둔다 */
    fun codeBlock(text: String) = "```\n" + text.replace("```", "`​``") + "\n```"
}
