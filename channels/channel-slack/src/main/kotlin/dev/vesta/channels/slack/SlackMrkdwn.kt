package dev.vesta.channels.slack

import dev.vesta.channels.MarkdownTable

/**
 * 답변 Markdown → Slack mrkdwn. 모든 글자에서 `& < >`를 이스케이프하므로 답변에 들어 있던 `<!channel>`·`<@U…>`
 * 같은 멘션이나 링크 문법은 글자로만 보인다. 링크는 http(s)만. 표는 고정폭 블록으로 보낸다.
 * Slack은 서식 기호가 글자에 붙어 있으면 그리지 않아서 서식 앞뒤에 폭 없는 공백을 둔다.
 */
object SlackMrkdwn {
    private val heading = Regex("^#{1,6}\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
    private const val ZWSP = "​"

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 받은 글의 이스케이프·링크 표기(`<https://…|글>`)를 보통 글로 되돌린다 */
    fun unescape(text: String) = text
        .replace(Regex("<(https?://[^|>]+)(?:\\|[^>]*)?>"), "$1")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** http(s) 주소만 링크로, 나머지는 글자로 */
    fun link(text: String, url: String): String =
        if (url.startsWith("https://") || url.startsWith("http://")) "<${escape(url).replace("|", "%7C")}|${escape(text).replace("|", "¦")}>" else escape(text)

    /** 안의 ``` 때문에 블록이 일찍 닫히지 않게 끊어 둔다 */
    fun codeBlock(text: String) = "```\n" + escape(text).replace("```", "`$ZWSP``") + "\n```"

    fun render(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    val code = mutableListOf<String>()
                    i++
                    // 닫히지 않은 블록은 끝까지 코드. Slack은 언어 표시를 못 해서 버린다
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    out += codeBlock(code.joinToString("\n"))
                }
                MarkdownTable.startsAt(lines, i) -> {
                    val (table, next) = MarkdownTable.read(lines, i)
                    out += codeBlock(table)
                    i = next
                    continue
                }
                // 인용 기호 >는 이스케이프하지 않아야 인용으로 그려진다
                trimmed.startsWith(">") -> out += "> " + inline(trimmed.removePrefix(">").trim())
                heading.matches(trimmed) -> out += bold(heading.find(trimmed)!!.groupValues[1].replace("**", ""))
                bullet.matches(line) -> bullet.find(line)!!.let { out += it.groupValues[1] + "• " + inline(it.groupValues[2]) }
                ordered.matches(line) -> ordered.find(line)!!.let { out += it.groupValues[1] + it.groupValues[2] + " " + inline(it.groupValues[3]) }
                // 빈 줄이 여러 개면 하나로
                line.isBlank() -> if (out.lastOrNull()?.isBlank() == false) out += ""
                else -> out += inline(line)
            }
            i++
        }
        return out.joinToString("\n").trim()
    }

    private fun bold(text: String) = "$ZWSP*${inline(text)}*$ZWSP"

    fun inline(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) { out.append(ZWSP).append('`').append(escape(text.substring(i + 1, end))).append('`').append(ZWSP); i = end + 1; continue }
            }
            if (c == '$' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '$') {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') { out.append(ZWSP).append('`').append(escape(text.substring(i + 1, end))).append('`').append(ZWSP); i = end + 1; continue }
            }
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) { out.append(bold(text.substring(i + 2, end))); i = end + 2; continue }
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) { out.append(ZWSP).append('_').append(escape(text.substring(i + 1, end))).append('_').append(ZWSP); i = end + 1; continue }
            }
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val end = text.indexOf(')', close + 2)
                    val url = if (end > close) text.substring(close + 2, end).trim() else ""
                    if (end > close && (url.startsWith("https://") || url.startsWith("http://"))) {
                        out.append(link(text.substring(i + 1, close), url))
                        i = end + 1
                        continue
                    }
                }
            }
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
