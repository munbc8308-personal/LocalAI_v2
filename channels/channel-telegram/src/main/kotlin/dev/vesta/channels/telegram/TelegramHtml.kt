package dev.vesta.channels.telegram

import dev.vesta.channels.MarkdownTable

/**
 * 답변 Markdown → Telegram HTML (parse_mode=HTML). 쓰는 태그: b, i, code, pre, a, blockquote.
 * 모든 글자는 이스케이프하므로 답변에 들어 있던 HTML은 글자 그대로 보인다. 링크는 http(s)만.
 * Telegram은 표를 못 그려서 표는 고정폭 블록으로 보낸다.
 */
object TelegramHtml {
    private val heading = Regex("^#{1,6}\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
    private val language = Regex("^[A-Za-z0-9_+#-]{1,20}$")

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttribute(text: String) = escape(text).replace("\"", "&quot;")

    /** http(s) 주소만 링크로, 나머지는 글자로 */
    fun link(text: String, url: String): String =
        if (url.startsWith("https://") || url.startsWith("http://")) "<a href=\"${escapeAttribute(url)}\">${escape(text)}</a>" else escape(text)

    fun render(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim().takeIf { language.matches(it) }
                    val code = mutableListOf<String>()
                    i++
                    // 닫히지 않은 블록은 끝까지 코드
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    val open = if (lang != null) "<pre><code class=\"language-$lang\">" else "<pre><code>"
                    out += open + escape(code.joinToString("\n")) + "</code></pre>"
                }
                MarkdownTable.startsAt(lines, i) -> {
                    val (table, next) = MarkdownTable.read(lines, i)
                    out += "<pre>" + escape(table) + "</pre>"
                    i = next
                    continue
                }
                trimmed.startsWith(">") -> {
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                    out += "<blockquote>" + inline(quote.joinToString("\n")) + "</blockquote>"
                    continue
                }
                heading.matches(trimmed) -> out += "<b>" + inline(heading.find(trimmed)!!.groupValues[1]) + "</b>"
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

    fun inline(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) { out.append("<code>").append(escape(text.substring(i + 1, end))).append("</code>"); i = end + 1; continue }
            }
            if (c == '$' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '$') {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') { out.append("<code>").append(escape(text.substring(i + 1, end))).append("</code>"); i = end + 1; continue }
            }
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) { out.append("<b>").append(inline(text.substring(i + 2, end))).append("</b>"); i = end + 2; continue }
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) { out.append("<i>").append(escape(text.substring(i + 1, end))).append("</i>"); i = end + 1; continue }
            }
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val end = text.indexOf(')', close + 2)
                    val url = if (end > close) text.substring(close + 2, end).trim() else ""
                    if (end > close && (url.startsWith("https://") || url.startsWith("http://"))) {
                        out.append("<a href=\"").append(escapeAttribute(url)).append("\">").append(escape(text.substring(i + 1, close))).append("</a>")
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
