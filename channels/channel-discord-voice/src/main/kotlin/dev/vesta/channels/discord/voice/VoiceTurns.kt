package dev.vesta.channels.discord.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 한 사람이 한 번 말한 소리 (48kHz 16비트 스테레오 big-endian, Discord가 풀어 준 그대로) */
class Utterance(val userId: String, val pcm: ByteArray) {
    val seconds: Double get() = pcm.size / BYTES_PER_SECOND.toDouble()

    companion object {
        const val BYTES_PER_SECOND = 48_000 * 4
    }
}

/**
 * 말 끊기 (PROJECT.md I-08). Discord는 말하지 않는 동안 소리를 보내지 않으므로 [endSilenceMillis] 동안 소리가 없으면 그 사람 말이 끝났다고 본다.
 * [minSeconds]보다 짧은 소리(기침·잡음)는 버리고, [maxSeconds]를 넘으면 그때까지를 한 번으로 끊는다. 스레드 안전
 */
class VoiceTurns(
    private val endSilenceMillis: Long = 800,
    private val minSeconds: Double = 0.4,
    private val maxSeconds: Double = 30.0,
) {
    private class Speaking(val buffer: ByteArrayOutputStream = ByteArrayOutputStream(), var lastAt: Long = 0)
    private val speaking = HashMap<String, Speaking>()

    @Synchronized
    fun onAudio(userId: String, pcm: ByteArray, now: Long) {
        val s = speaking.getOrPut(userId) { Speaking() }
        s.buffer.writeBytes(pcm)
        s.lastAt = now
    }

    /** 끝난 말들 */
    @Synchronized
    fun finished(now: Long): List<Utterance> {
        val done = speaking.filter { (_, s) -> now - s.lastAt >= endSilenceMillis || s.buffer.size() >= maxSeconds * Utterance.BYTES_PER_SECOND }
        done.keys.forEach(speaking::remove)
        return done.map { (user, s) -> Utterance(user, s.buffer.toByteArray()) }.filter { it.seconds >= minSeconds }
    }

    @Synchronized
    fun clear() = speaking.clear()
}

object Pcm {
    /**
     * 합성 음성 앞뒤의 무음을 잘라 [keepMillis]만 남긴다 (48kHz 스테레오 BE). `say`는 끝에 0.2초쯤 무음을 붙여
     * 조각을 이어 읽으면 사이가 벌어진다
     */
    fun trimSilence(pcm: ByteArray, threshold: Int = 400, keepMillis: Int = 80): ByteArray {
        val frames = pcm.size / 4
        if (frames == 0) return pcm
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.BIG_ENDIAN)
        fun loud(i: Int) = maxOf(kotlin.math.abs(buffer.getShort(i * 4).toInt()), kotlin.math.abs(buffer.getShort(i * 4 + 2).toInt())) > threshold
        val first = (0 until frames).firstOrNull(::loud) ?: return ByteArray(0)
        val last = (frames - 1 downTo 0).first(::loud)
        val keep = 48 * keepMillis
        val start = maxOf(0, first - keep)
        val end = minOf(frames, last + 1 + keep)
        return pcm.copyOfRange(start * 4, end * 4)
    }

    /** 48kHz 스테레오 BE → 16kHz 모노 16비트 WAV (음성 인식용, 3샘플·두 채널 평균) */
    fun toWav16k(pcm: ByteArray): ByteArray {
        val frames = pcm.size / 4
        val count = frames / 3
        val out = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count * 2)
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until count) {
            var sum = 0
            for (k in 0 until 3) {
                val at = (i * 3 + k) * 4
                sum += input.getShort(at) + input.getShort(at + 2)
            }
            out.putShort((sum / 6).toShort())
        }
        return out.array()
    }
}

/** 호출어 ("베스타 오늘 날씨 어때?"). 여러 사람이 있는 음성 채널에서는 호출어로 시작한 말에만 답한다 */
object WakeWord {
    private val LEADING = Regex("^[\\s,.!?~…]+")
    private val SUFFIX = Regex("^(야|아|님|씨)?[\\s,.!?~…]*")

    /** 호출어로 시작하면 나머지 말(비었으면 ""), 아니면 null. 대소문자·앞뒤 문장부호 무시 */
    fun strip(text: String, words: List<String>): String? {
        val trimmed = text.replace(LEADING, "")
        val word = words.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return null
        return trimmed.substring(word.length).replaceFirst(SUFFIX, "").trim()
    }

    /** 말하는 중에 끊는 말 */
    private val STOP = Regex("^(그만|멈춰|조용|스톱|stop)", RegexOption.IGNORE_CASE)
    fun isStop(text: String) = STOP.containsMatchIn(text.trim())
}

/**
 * 답변을 문장 단위로 끊는다 (생성되는 대로 읽기). 웹 UI 음성 대화(SpeechText.cut)와 같은 규칙:
 * 문장 끝·줄바꿈까지, 8자 미만이면 다음 문장과 붙이고, 닫히지 않은 코드 블록 앞까지만
 */
object Sentences {
    private val END = Regex("[.!?。…]+[\"')\\]]*(\\s|$)|\\n+")

    /**
     * [from]부터 읽을 데까지의 끝. 문장 끝에서만 자르되 [minChars]보다 짧으면 다음 문장까지 붙인다.
     * 첫 조각은 짧게(빨리 말하기 시작), 그다음은 길게 잡아 조각 사이 끊김·억양이 다시 시작되는 일을 줄인다 (M19 실사용: 문장마다 끊겨 답답함)
     */
    fun cut(text: String, from: Int, finished: Boolean, minChars: Int = 8): Int {
        if (finished) return text.length
        val all = text.substring(from)
        val rest = if (all.split("```").size % 2 == 0) all.substring(0, all.lastIndexOf("```")) else all
        var end = -1
        for (m in END.findAll(rest)) {
            val candidate = m.range.last + 1
            end = candidate
            if (rest.substring(0, candidate).trim().length >= minChars) return from + candidate
        }
        return from
    }
}
