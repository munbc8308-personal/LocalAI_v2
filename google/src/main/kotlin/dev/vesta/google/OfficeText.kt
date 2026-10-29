package dev.vesta.google

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** 드라이브 파일 → 글 (GW-04). .docx·.xlsx는 zip 속 XML에서 글자만, .pdf는 PDFBox. 모르는 형식은 null */
object OfficeText {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun extract(name: String, mimeType: String, bytes: ByteArray): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mimeType == DOCX || ext == "docx" -> docx(bytes)
            mimeType == XLSX || ext == "xlsx" -> xlsx(bytes)
            mimeType == "application/pdf" || ext == "pdf" -> Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
            mimeType.startsWith("text/") || mimeType in TEXT_TYPES || ext in TEXT_EXTENSIONS -> bytes.toString(Charsets.UTF_8)
            else -> null
        }
    }

    /** 문단마다 한 줄 */
    fun docx(bytes: ByteArray): String {
        val xml = entries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return ""
        val doc = parse(xml)
        return doc.getElementsByTagName("w:p").let { ps ->
            (0 until ps.length).joinToString("\n") { i -> texts(ps.item(i) as Element, "w:t") }
        }.trim()
    }

    /** 시트마다 "## 시트 n" 다음에 줄마다 탭으로 나눈 칸 */
    fun xlsx(bytes: ByteArray): String {
        val files = entries(bytes) { it == "xl/sharedStrings.xml" || SHEET.matches(it) }
        val shared = files["xl/sharedStrings.xml"]?.let { xml ->
            val si = parse(xml).getElementsByTagName("si")
            (0 until si.length).map { texts(si.item(it) as Element, "t") }
        }.orEmpty()
        return files.keys.filter { SHEET.matches(it) }.sortedBy { SHEET.find(it)!!.groupValues[1].toInt() }.joinToString("\n\n") { key ->
            val rows = parse(files.getValue(key)).getElementsByTagName("row")
            val lines = (0 until minOf(rows.length, MAX_ROWS)).map { r ->
                val cells = (rows.item(r) as Element).getElementsByTagName("c")
                (0 until cells.length).joinToString("\t") { c ->
                    val cell = cells.item(c) as Element
                    val value = cell.getElementsByTagName("v").item(0)?.textContent
                    when (cell.getAttribute("t")) {
                        "s" -> value?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> texts(cell, "t")
                        else -> value.orEmpty()
                    }
                }
            }
            "## 시트 ${SHEET.find(key)!!.groupValues[1]}\n" + lines.joinToString("\n")
        }
    }

    private fun texts(element: Element, tag: String): String {
        val nodes = element.getElementsByTagName(tag)
        return (0 until nodes.length).joinToString("") { nodes.item(it).textContent }
    }

    /** zip에서 고른 항목만 (항목 하나 20MB까지: 압축 폭탄 대비) */
    private fun entries(bytes: ByteArray, want: (String) -> Boolean): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!want(entry.name)) continue
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    require(out.size() <= MAX_ENTRY) { "파일 속 항목이 너무 큽니다" }
                }
                put(entry.name, out.toByteArray())
            }
        }
    }

    /** 외부 엔티티·DTD를 막은 XML 해석 (XXE 대비) */
    private fun parse(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    private val SHEET = Regex("^xl/worksheets/sheet(\\d+)\\.xml$")
    private const val MAX_ROWS = 2_000
    private const val MAX_ENTRY = 20 * 1024 * 1024
    private val TEXT_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")
    private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "html")
}
