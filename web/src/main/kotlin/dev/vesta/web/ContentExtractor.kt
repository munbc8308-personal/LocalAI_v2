package dev.vesta.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class ExtractedContent(val title: String, val text: String, val publishedDate: String?)

/**
 * HTML에서 본문만 뽑는다 (PROJECT.md W-02). Readability 방식을 단순화한 것:
 * 메뉴·광고·스크립트를 지우고, article/main 또는 문단 글자가 가장 많은 블록을 본문으로 고른다.
 */
object ContentExtractor {
    private const val NOISE = "script, style, noscript, iframe, svg, form, nav, header, footer, aside, button, " +
        "[role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true], .ad, .ads, .advert, .banner, " +
        ".cookie, .popup, .modal, .sidebar, .comments, .comment, .share, .related, .breadcrumb"
    private const val TEXT_BLOCKS = "h1, h2, h3, h4, p, li, pre, blockquote, td, th, dt, dd"

    fun extract(html: String, baseUrl: String): ExtractedContent {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
        val published = doc.selectFirst("meta[property=article:published_time], meta[name=date], time[datetime]")
            ?.let { it.attr("content").ifBlank { it.attr("datetime") } }?.takeIf { it.isNotBlank() }
        doc.select(NOISE).remove()
        val root = mainElement(doc)
        return ExtractedContent(title.trim(), blocksText(root), published)
    }

    fun plainText(text: String): ExtractedContent = ExtractedContent("", text.trim(), null)

    private fun mainElement(doc: Document): Element {
        doc.selectFirst("article, main, [role=main], #content, .post, .article, .entry-content")
            ?.takeIf { paragraphChars(it) > 400 }
            ?.let { return it }
        // 문단 글자 수가 가장 많은 블록의 부모를 본문으로 본다
        val best = doc.select("div, section, td").maxByOrNull { paragraphChars(it, directOnly = true) }
        return best?.takeIf { paragraphChars(it) > 200 } ?: doc.body()
    }

    private fun paragraphChars(element: Element, directOnly: Boolean = false): Int =
        (if (directOnly) element.children().filter { it.`is`("p, pre, li, ul, ol") } else element.select("p, pre, li"))
            .sumOf { it.text().length }

    private fun blocksText(root: Element): String {
        val lines = mutableListOf<String>()
        for (block in root.select(TEXT_BLOCKS)) {
            // 안쪽 블록이 따로 잡히므로 블록을 품은 블록은 건너뛴다 (중복 방지)
            if (block.select(TEXT_BLOCKS).size > 1 && !block.`is`("pre, td, th")) continue
            val text = if (block.`is`("pre")) block.wholeText().trimEnd() else block.text().trim()
            if (text.length < 2) continue
            lines += when {
                block.`is`("h1, h2, h3, h4") -> "## $text"
                block.`is`("li") -> "- $text"
                else -> text
            }
        }
        val joined = lines.distinct().joinToString("\n\n")
        return joined.ifBlank { root.text() }
    }
}
