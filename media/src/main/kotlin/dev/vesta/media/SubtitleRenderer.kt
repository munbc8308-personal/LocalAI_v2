package dev.vesta.media

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 영상 자막·끝 화면을 PNG로 그린다 (PROJECT.md G-02). Homebrew ffmpeg에는 글자 필터(drawtext·subtitles)가 없어서 Java2D로 그려 영상 위에 겹친다.
 * 한글 글꼴은 macOS 기본(Apple SD Gothic Neo). 화면 밖으로 넘치지 않게 줄을 나누고, 넘치면 마지막 줄을 줄인다
 */
object SubtitleRenderer {
    init { System.setProperty("java.awt.headless", "true") }

    private const val FONT = "Apple SD Gothic Neo"
    private const val MAX_LINES = 3

    /** 화면 아래 자막 (투명 배경 + 반투명 상자) */
    fun subtitle(text: String, width: Int, height: Int): ByteArray = draw(width, height) { g ->
        if (text.isBlank()) return@draw
        val size = (minOf(width, height) * 0.045).toInt().coerceAtLeast(18)
        g.font = Font(FONT, Font.BOLD, size)
        val lines = wrap(text.trim(), g.fontMetrics::stringWidth, (width * 0.86).toInt())
        val lineHeight = (size * 1.35).toInt()
        val pad = size / 2
        val boxHeight = lines.size * lineHeight + pad * 2
        val boxWidth = (lines.maxOf { g.fontMetrics.stringWidth(it) } + pad * 3).coerceAtMost(width - pad * 2)
        val top = height - boxHeight - (height * 0.06).toInt()
        g.color = Color(0, 0, 0, 150)
        g.fill(RoundRectangle2D.Float(((width - boxWidth) / 2).toFloat(), top.toFloat(), boxWidth.toFloat(), boxHeight.toFloat(), size.toFloat(), size.toFloat()))
        lines.forEachIndexed { i, line ->
            val x = (width - g.fontMetrics.stringWidth(line)) / 2
            val y = top + pad + i * lineHeight + g.fontMetrics.ascent
            outlined(g, line, x, y, size)
        }
    }

    /** 끝 화면: 검은 바탕에 제목과 "AI 생성" (G-02 원칙) */
    fun endCard(title: String, note: String, width: Int, height: Int): ByteArray = draw(width, height, Color.BLACK) { g ->
        val big = (minOf(width, height) * 0.07).toInt()
        g.font = Font(FONT, Font.BOLD, big)
        val lines = wrap(title.trim(), g.fontMetrics::stringWidth, (width * 0.8).toInt())
        val lineHeight = (big * 1.3).toInt()
        var y = height / 2 - lines.size * lineHeight / 2
        lines.forEach { line ->
            g.color = Color.WHITE
            g.drawString(line, (width - g.fontMetrics.stringWidth(line)) / 2, y + g.fontMetrics.ascent)
            y += lineHeight
        }
        g.font = Font(FONT, Font.PLAIN, big / 2)
        g.color = Color(200, 200, 200)
        g.drawString(note, (width - g.fontMetrics.stringWidth(note)) / 2, y + big)
    }

    /** 낱말 단위로(한국어는 띄어쓰기), 낱말 하나가 너무 길면 글자 단위로 나눈다 */
    internal fun wrap(text: String, measure: (String) -> Int, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        fun push(word: String) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate) <= maxWidth) { current = candidate; return }
            if (current.isNotEmpty()) lines += current
            current = ""
            if (measure(word) <= maxWidth) { current = word; return }
            var part = ""
            for (c in word) {
                if (measure(part + c) > maxWidth && part.isNotEmpty()) { lines += part; part = "" }
                part += c
            }
            current = part
        }
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach(::push)
        if (current.isNotEmpty()) lines += current
        if (lines.size <= MAX_LINES) return lines
        val kept = lines.take(MAX_LINES).toMutableList()
        var last = kept.last()
        while (last.isNotEmpty() && measure("$last…") > maxWidth) last = last.dropLast(1)
        kept[MAX_LINES - 1] = "$last…"
        return kept
    }

    private fun outlined(g: java.awt.Graphics2D, text: String, x: Int, y: Int, size: Int) {
        val shape = g.font.createGlyphVector(g.fontRenderContext, text).getOutline(x.toFloat(), y.toFloat())
        g.color = Color(0, 0, 0, 220)
        g.stroke = BasicStroke(size / 8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.color = Color.WHITE
        g.fill(shape)
    }

    private fun draw(width: Int, height: Int, background: Color? = null, block: (java.awt.Graphics2D) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (background != null) { g.color = background; g.fillRect(0, 0, width, height) }
            block(g)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
