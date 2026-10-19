package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Blockquote
import org.jetbrains.compose.web.dom.Br
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Em
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import org.jetbrains.compose.web.dom.Ul

/**
 * 답변 Markdown을 DOM 요소로 그린다. HTML 문자열을 끼워 넣지 않으므로(innerHTML 없음) 모델 답변이나
 * 웹 자료에 들어 있던 HTML·스크립트는 글자 그대로 보인다. 링크는 http(s)만 연다.
 * 지원: 코드 블록(복사 버튼), 제목, 목록, 인용, 표, 굵게·기울임·인라인 코드, 링크, 출처 번호 [n].
 */
@Composable
fun Markdown(source: String) {
    for (block in MarkdownParser.blocks(source)) {
        when (block) {
            is MdBlock.Code -> CodeBlock(block.language, block.text)
            is MdBlock.Heading -> if (block.level <= 2) H3 { Inline(block.text) } else H4 { Inline(block.text) }
            is MdBlock.ListBlock -> {
                val items: @Composable () -> Unit = { block.items.forEach { Li { Inline(it) } } }
                if (block.ordered) Ol { items() } else Ul { items() }
            }
            is MdBlock.Quote -> Blockquote { Inline(block.text) }
            is MdBlock.TableBlock -> Div({ classes("table-wrap") }) {
                Table {
                    Thead { Tr { block.header.forEach { Th { Inline(it) } } } }
                    Tbody { block.rows.forEach { row -> Tr { row.forEach { Td { Inline(it) } } } } }
                }
            }
            is MdBlock.Paragraph -> P {
                block.lines.forEachIndexed { i, line ->
                    if (i > 0) Br()
                    Inline(line)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String?, text: String) {
    var copied by remember { mutableStateOf(false) }
    Div({ classes("code") }) {
        Div({ classes("code-bar") }) {
            Span { Text(language ?: "코드") }
            Button({
                classes("link")
                onClick {
                    window.navigator.asDynamic().clipboard?.writeText(text)
                    copied = true
                    window.setTimeout({ copied = false }, 1500)
                }
            }) { Text(if (copied) "복사됨" else "복사") }
        }
        Pre { Code { Text(text) } }
    }
}

@Composable
private fun Inline(text: String) {
    for (span in MarkdownParser.inline(text)) {
        when (span) {
            is MdSpan.Plain -> Text(span.text)
            is MdSpan.CodeSpan -> Code { Text(span.text) }
            is MdSpan.Bold -> B { Text(span.text) }
            is MdSpan.Italic -> Em { Text(span.text) }
            is MdSpan.Citation -> Span({ classes("cite") }) { Text("[${span.number}]") }
            is MdSpan.Link -> A(span.url, {
                target(ATarget.Blank)
                attr("rel", "noopener noreferrer")
            }) { Text(span.text) }
        }
    }
}

sealed interface MdBlock {
    data class Code(val language: String?, val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class TableBlock(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Paragraph(val lines: List<String>) : MdBlock
}

sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class CodeSpan(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Citation(val number: Int) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

/** 스트리밍 중인 미완성 답변도 그릴 수 있게, 닫히지 않은 코드 블록은 끝까지 코드로 본다 */
object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ordered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun blocks(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(paragraph.toList())
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    out += MdBlock.Code(language, code.joinToString("\n"))
                }
                trimmed.isEmpty() -> flush()
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                bullet.matches(line) || ordered.matches(line) -> {
                    flush()
                    val isOrdered = ordered.matches(line)
                    val pattern = if (isOrdered) ordered else bullet
                    val items = mutableListOf<String>()
                    while (i < lines.size && pattern.matches(lines[i])) items += pattern.find(lines[i])!!.groupValues[1].also { i++ }
                    out += MdBlock.ListBlock(isOrdered, items)
                    continue
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                    out += MdBlock.Quote(quote.joinToString(" "))
                    continue
                }
                trimmed.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) rows += cells(lines[i++].trim())
                    out += MdBlock.TableBlock(header, rows)
                    continue
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) out += MdSpan.Plain(plain.toString())
            plain.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // `코드`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) { flush(); out += MdSpan.CodeSpan(text.substring(i + 1, end)); i = end + 1; continue }
            }
            // $수식$ — 수식 렌더러 없이 코드 글꼴로 보여 준다 ("$5" 같은 금액은 닫는 $가 없어 그대로)
            if (c == '$' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '$') {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') { flush(); out += MdSpan.CodeSpan(text.substring(i + 1, end)); i = end + 1; continue }
            }
            // **굵게**
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) { flush(); out += MdSpan.Bold(text.substring(i + 2, end)); i = end + 2; continue }
            }
            // *기울임* (단어 안의 _는 snake_case일 수 있어 *만 본다)
            if (c == '*' && i + 1 < text.length && text[i + 1] != ' ') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) { flush(); out += MdSpan.Italic(text.substring(i + 1, end)); i = end + 1; continue }
            }
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i) {
                    val label = text.substring(i + 1, close)
                    // [텍스트](http…)
                    if (close + 1 < text.length && text[close + 1] == '(') {
                        val end = text.indexOf(')', close + 2)
                        val url = if (end > close) text.substring(close + 2, end).trim() else ""
                        if (end > close && (url.startsWith("https://") || url.startsWith("http://"))) {
                            flush(); out += MdSpan.Link(label, url); i = end + 1; continue
                        }
                    }
                    // 출처 번호 [1], [1, 2]
                    val numbers = label.split(',').map { it.trim().toIntOrNull() }
                    if (numbers.isNotEmpty() && numbers.all { it != null }) {
                        flush(); numbers.forEach { out += MdSpan.Citation(it!!) }; i = close + 1; continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flush()
        return out
    }
}
