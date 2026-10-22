package dev.vesta.channels.discord

import java.util.Base64

/** Discord 음성 메시지에 붙이는 길이·파형 (PROJECT.md V-03, M18). Discord는 둘 다 있어야 음성 메시지로 보여 준다 */
internal object OggOpus {
    /**
     * OGG Opus 길이(초): 마지막 페이지의 granule position − OpusHead의 pre-skip, 48kHz 기준.
     * 형식이 다르면 0
     */
    fun durationSeconds(ogg: ByteArray): Double {
        var granule = -1L
        var preSkip = 0
        var i = 0
        while (i + 27 <= ogg.size) {
            if (ogg[i] == 'O'.code.toByte() && ogg[i + 1] == 'g'.code.toByte() && ogg[i + 2] == 'g'.code.toByte() && ogg[i + 3] == 'S'.code.toByte()) {
                val position = (0 until 8).fold(0L) { acc, k -> acc or ((ogg[i + 6 + k].toLong() and 0xFF) shl (8 * k)) }
                if (position >= 0) granule = position
                val segments = ogg[i + 26].toInt() and 0xFF
                val bodyStart = i + 27 + segments
                if (i + 27 + segments > ogg.size) break
                val bodyLength = (0 until segments).sumOf { ogg[i + 27 + it].toInt() and 0xFF }
                // 첫 페이지의 OpusHead: 매직 8바이트, 버전 1, 채널 1, pre-skip 2바이트(LE)
                if (bodyStart + 12 <= ogg.size && String(ogg, bodyStart, 8, Charsets.US_ASCII) == "OpusHead") {
                    preSkip = (ogg[bodyStart + 10].toInt() and 0xFF) or ((ogg[bodyStart + 11].toInt() and 0xFF) shl 8)
                }
                i = bodyStart + bodyLength
            } else i++
        }
        return if (granule <= 0) 0.0 else maxOf(0.0, (granule - preSkip) / 48_000.0)
    }

    /** 파형: 0~255 값 [points]개를 base64로. 소리 내용을 분석하지 않고 길이에 맞춘 고른 모양을 그린다 */
    fun waveform(seconds: Double, points: Int = 64): String {
        val n = points.coerceIn(1, 256)
        val bytes = ByteArray(n) { k -> (96 + 64 * kotlin.math.sin(k * 0.7 + seconds)).toInt().coerceIn(0, 255).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
