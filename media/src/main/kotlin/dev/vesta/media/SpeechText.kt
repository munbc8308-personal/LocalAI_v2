package dev.vesta.media

/**
 * 답변 Markdown을 읽어 주기 좋은 글로 (PROJECT.md V-03: 코드·표·출처는 읽지 않는다).
 * 코드 블록·표는 "(코드 생략)"·"(표 생략)", 출처 번호·주소·서식 기호는 지운다. 길면 [maxChars] 안에서 문장 끝에서 자른다.
 */
object SpeechText {
    private val citation = Regex("\\s*\\[\\d+(?:\\s*,\\s*\\d+)*]")
    private val link = Regex("\\[([^\\]]+)]\\((?:[^)]+)\\)")
    private val url = Regex("https?://\\S+")
    private val inlineCode = Regex("`([^`]*)`")
    private val emphasis = Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val heading = Regex("^#{1,6}\\s+")
    private val bullet = Regex("^\\s*(?:[-*+•]|\\d+[.)])\\s+")
    private val tableRow = Regex("^\\s*\\|.*\\|\\s*$")

    fun prepare(markdown: String, maxChars: Int): String {
        val out = mutableListOf<String>()
        var inCode = false
        var inTable = false
        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trim()
            if (line.startsWith("```")) {
                if (!inCode) out += "(코드 생략)"
                inCode = !inCode
                continue
            }
            if (inCode) continue
            if (tableRow.matches(line)) {
                if (!inTable) out += "(표 생략)"
                inTable = true
                continue
            }
            inTable = false
            // 시스템이 붙인 출처 목록·안내는 읽지 않는다
            if (line == "출처" || line.startsWith("※")) break
            var text = line.replace(heading, "").replace(bullet, "").removePrefix(">").trim()
            text = text.replace(link, "$1").replace(url, "").replace(citation, "").replace(inlineCode, "$1").replace(emphasis, "$2").trim()
            if (text.isNotBlank()) out += text
        }
        val joined = out.joinToString("\n").replace(Regex("\n{2,}"), "\n").trim()
        if (joined.length <= maxChars) return joined
        val cut = joined.take(maxChars)
        // 문장 끝(". ", "다.", 줄 끝) 바로 뒤에서 자른다
        val end = maxOf(cut.lastIndexOf(". ") + 1, cut.lastIndexOf("다.").let { if (it < 0) -1 else it + 2 }, cut.lastIndexOf('\n'))
        return (if (end > maxChars / 2) cut.take(end) else cut).trim() + "\n이하 생략."
    }
}
