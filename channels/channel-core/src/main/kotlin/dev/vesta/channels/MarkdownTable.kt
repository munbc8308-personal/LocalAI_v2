package dev.vesta.channels

/** 표를 그리지 못하는 메신저용: Markdown 표 → 열 너비를 맞춘 고정폭 글 */
object MarkdownTable {
    private val separator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** [lines]의 [index]에서 표가 시작하는지 (머리 줄 + 구분 줄) */
    fun startsAt(lines: List<String>, index: Int) =
        lines[index].trim().startsWith("|") && index + 1 < lines.size && separator.matches(lines[index + 1])

    /** [index]에서 시작하는 표를 읽어 (고정폭 글, 다음 줄 번호)를 돌려준다 */
    fun read(lines: List<String>, index: Int): Pair<String, Int> {
        val rows = mutableListOf(cells(lines[index]))
        var i = index + 2
        while (i < lines.size && lines[i].trim().startsWith("|")) rows += cells(lines[i++])
        return format(rows) to i
    }

    private fun cells(row: String) = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim().replace("**", "").replace("`", "") }

    /** 열 너비를 맞춘 표. 한글은 두 칸으로 센다 */
    private fun format(rows: List<List<String>>): String {
        val columns = rows.maxOf { it.size }
        val widths = (0 until columns).map { c -> rows.maxOf { width(it.getOrElse(c) { "" }) } }
        return rows.mapIndexed { r, row ->
            val line = (0 until columns).joinToString(" | ") { c -> row.getOrElse(c) { "" }.let { it + " ".repeat(widths[c] - width(it)) } }.trimEnd()
            if (r == 0) line + "\n" + widths.joinToString("-+-") { "-".repeat(it) } else line
        }.joinToString("\n")
    }

    private fun width(text: String) = text.sumOf { if (it.code in 0x1100..0xFFDC && !it.isWhitespace()) 2L else 1L }.toInt()
}
