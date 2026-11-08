package dev.vesta.agents

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 붙인 그림을 만들 크기로 (V-11·G-07). PNG·JPEG·GIF만 읽는다 (JDK ImageIO). 못 읽으면 null: 부르는 쪽이 원본을 그대로 쓴다
 */
internal object ImageFit {
    /** 너비·높이 */
    fun size(bytes: ByteArray): Pair<Int, Int>? = read(bytes)?.let { it.width to it.height }

    /** 가운데를 기준으로 [width]×[height]를 채우게 줄이고 넘치는 쪽을 자른다 (늘이지 않는다). PNG */
    fun cover(bytes: ByteArray, width: Int, height: Int): ByteArray? {
        val source = read(bytes) ?: return null
        val scale = maxOf(width.toDouble() / source.width, height.toDouble() / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(width)
        val h = (source.height * scale).toInt().coerceAtLeast(height)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, (width - w) / 2, (height - h) / 2, w, h, null)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
    }

    /** 가로·세로·정사각 (긴 쪽이 짧은 쪽의 1.15배를 넘으면 그쪽) */
    fun aspect(bytes: ByteArray): String? = size(bytes)?.let { (w, h) ->
        when {
            w > h * 1.15 -> "landscape"
            h > w * 1.15 -> "portrait"
            else -> "square"
        }
    }

    private fun read(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
}
