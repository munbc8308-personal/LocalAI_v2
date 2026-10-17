package dev.vesta.knowledge

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes

/** 파싱 결과의 문단 하나. 줄 번호(텍스트) 또는 쪽 번호(PDF)를 위치로 가진다. */
data class TextBlock(
    val text: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val page: Int? = null,
    /** "2. 사용 환경 > 2.2 메모리 예산" 같은 제목 경로 (Markdown) */
    val heading: String? = null,
)

data class ParsedDocument(val title: String, val blocks: List<TextBlock>)

/** 지원 형식: .txt, .md, .pdf (PROJECT.md K-03). */
object DocumentParser {
    val supportedExtensions = setOf("txt", "md", "markdown", "pdf")

    fun supports(path: Path) = path.extension.lowercase() in supportedExtensions

    fun parse(path: Path, bytes: ByteArray = path.readBytes()): ParsedDocument = when (path.extension.lowercase()) {
        "md", "markdown" -> MarkdownParser.parse(bytes.decodeToString(), path.nameWithoutExtension)
        "pdf" -> PdfParser.parse(bytes, path.nameWithoutExtension)
        else -> PlainTextParser.parse(bytes.decodeToString(), path.nameWithoutExtension)
    }
}

/** 빈 줄로 문단을 나눈다. */
internal object PlainTextParser {
    fun parse(text: String, fallbackTitle: String): ParsedDocument {
        val blocks = mutableListOf<TextBlock>()
        val buffer = StringBuilder()
        var start = 0
        text.lines().forEachIndexed { index, line ->
            if (line.isBlank()) {
                if (buffer.isNotBlank()) blocks += TextBlock(buffer.toString().trimEnd(), start + 1, index)
                buffer.clear()
            } else {
                if (buffer.isEmpty()) start = index
                buffer.appendLine(line)
            }
        }
        if (buffer.isNotBlank()) blocks += TextBlock(buffer.toString().trimEnd(), start + 1, text.lines().size)
        return ParsedDocument(fallbackTitle, blocks)
    }
}

/**
 * 제목(#)으로 구역을 나누고 구역 안은 빈 줄로 문단을 나눈다.
 * 코드 블록(```) 안의 # 은 제목으로 보지 않고, 코드 블록은 문단 하나로 둔다.
 */
internal object MarkdownParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")

    fun parse(text: String, fallbackTitle: String): ParsedDocument {
        val lines = text.lines()
        val blocks = mutableListOf<TextBlock>()
        val headings = arrayOfNulls<String>(6)
        var title: String? = null
        var inFence = false
        val buffer = StringBuilder()
        var start = 0

        fun currentHeading() = headings.filterNotNull().joinToString(" > ").ifEmpty { null }
        fun flush(endLine: Int) {
            if (buffer.isNotBlank()) blocks += TextBlock(buffer.toString().trimEnd(), start + 1, endLine, heading = currentHeading())
            buffer.clear()
        }

        lines.forEachIndexed { index, line ->
            if (line.trimStart().startsWith("```")) inFence = !inFence
            val heading = if (inFence) null else headingRegex.find(line)
            when {
                heading != null -> {
                    flush(index)
                    val level = heading.groupValues[1].length
                    headings[level - 1] = heading.groupValues[2]
                    for (deeper in level until headings.size) headings[deeper] = null
                    if (level == 1 && title == null) title = heading.groupValues[2]
                }
                line.isBlank() && !inFence -> flush(index)
                else -> {
                    if (buffer.isEmpty()) start = index
                    buffer.appendLine(line)
                }
            }
        }
        flush(lines.size)
        return ParsedDocument(title ?: fallbackTitle, blocks)
    }
}

/** 쪽마다 글자를 뽑아 빈 줄 기준 문단으로 나눈다. 스캔 PDF(글자 없음)는 빈 결과 (OCR은 V-05). */
internal object PdfParser {
    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedDocument = Loader.loadPDF(bytes).use { pdf ->
        val title = pdf.documentInformation?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val stripper = PDFTextStripper()
        val blocks = (1..pdf.numberOfPages).flatMap { page ->
            stripper.startPage = page
            stripper.endPage = page
            stripper.getText(pdf)
                .split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { TextBlock(it, page = page) }
        }
        ParsedDocument(title, blocks)
    }
}

/** 다른 모듈(웹 본문 등)이 일반 텍스트를 문단으로 나눌 때 쓴다. */
object PlainTextParserFacade {
    fun parse(text: String, title: String): ParsedDocument = PlainTextParser.parse(text, title)
}
