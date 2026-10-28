package dev.vesta.server

import dev.vesta.channels.discord.voice.Sentences
import dev.vesta.core.agent.AgentEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

/**
 * 웹소켓 음성 대화 한 연결 (M37 RV-02·RV-03·RV-05). 브라우저가 16kHz 모노 PCM16 소리를 계속 보내면:
 * - 소리 크기로 말 시작·끝을 찾고([Endpointer]), 말하는 동안 [PARTIAL_MS]마다 지금까지 들은 소리를 다시 받아 적어 중간 자막(`partial`)을 보낸다
 * - 말이 끝나면 확정(`final`)하고 대화 서비스에 보낸 답을 글(`token`)과 문장별 소리(바이너리, m4a)로 흘려보낸다
 * - 답하는 중에 사용자가 말하기 시작하면 답을 멈추고 `interrupt`를 보낸다 (스피커 소리가 남을 수 있어 기준을 높인다)
 */
internal class VoiceSession(
    private val scope: CoroutineScope,
    private val transcribe: suspend (ByteArray) -> String,
    private val answer: (String) -> Flow<AgentEvent>,
    private val speak: suspend (String) -> ByteArray?,
    private val sendText: suspend (String) -> Unit,
    private val sendAudio: suspend (ByteArray) -> Unit,
) {
    private val endpointer = Endpointer()
    private val utterance = ByteArrayOutputStream()
    private var hearing = false
    private var sinceSnapshot = 0.0
    private var partial: Job? = null
    private var reply: Job? = null

    /** 브라우저가 보낸 소리 조각 (PCM16 LE, 16kHz 모노) */
    suspend fun onAudio(pcm: ByteArray) {
        val replying = reply?.isActive == true
        when (endpointer.feed(pcm, replying)) {
            Endpointer.Event.START -> {
                if (replying) interrupt()
                hearing = true
                utterance.reset()
                endpointer.preRoll().forEach { utterance.write(it) }
                sinceSnapshot = 0.0
            }
            Endpointer.Event.END -> if (hearing) { utterance.write(pcm); finish(); return }
            Endpointer.Event.NONE -> Unit
        }
        if (!hearing) return
        utterance.write(pcm)
        sinceSnapshot += pcm.size / 2 / SAMPLE_RATE * 1000.0
        if (utterance.size() >= MAX_BYTES) { finish(); return }
        if (sinceSnapshot >= PARTIAL_MS && partial?.isActive != true) {
            sinceSnapshot = 0.0
            val snapshot = utterance.toByteArray()
            partial = scope.launch {
                runCatching { transcribe(wav(snapshot)) }.getOrNull()?.takeIf { it.isNotBlank() && hearing }?.let { event("partial", it) }
            }
        }
    }

    /** 사용자가 화면에서 끊었을 때 */
    suspend fun interrupt() {
        if (reply?.isActive == true) {
            reply?.cancel()
            reply = null
            event("interrupt", "")
        }
    }

    private suspend fun finish() {
        hearing = false
        partial?.cancel()
        val audio = utterance.toByteArray()
        utterance.reset()
        if (audio.size < MIN_BYTES) return
        reply = scope.launch {
            val text = try { transcribe(wav(audio)).trim() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.warn(e) { "음성 인식 실패" }
                event("error", "말을 알아듣지 못했습니다")
                return@launch
            }
            if (text.isBlank()) { event("idle", ""); return@launch }
            event("final", text)
            respond(text)
        }
    }

    /** 답 글을 흘려보내면서 문장 묶음마다 미리 소리로 만들고, 순서대로 보낸다 */
    private suspend fun respond(question: String) = kotlinx.coroutines.coroutineScope {
        val pieces = Channel<Deferred<ByteArray?>>(Channel.UNLIMITED)
        val sender = launch { for (piece in pieces) piece.await()?.let { sendAudio(it) } }
        val text = StringBuilder()
        var spoken = 0
        var count = 0
        fun cut(finished: Boolean) {
            val end = Sentences.cut(text.toString(), spoken, finished, minChars = if (count == 0) 8 else 60)
            if (end <= spoken) return
            val piece = text.substring(spoken, end)
            spoken = end
            count++
            if (piece.isNotBlank()) pieces.trySend(async { runCatching { speak(piece) }.getOrNull() })
        }
        answer(question).collect { e ->
            when (e) {
                is AgentEvent.SessionReady -> event("session", e.sessionId.value)
                is AgentEvent.Token -> { text.append(e.text); event("token", e.text); cut(false) }
                is AgentEvent.Error -> event("error", e.message)
                else -> Unit
            }
        }
        cut(true)
        pieces.close()
        sender.join()
        event("done", "")
    }

    private suspend fun event(type: String, text: String) =
        sendText(buildJsonObject { put("type", type); put("text", text) }.toString())

    companion object {
        const val SAMPLE_RATE = 16_000.0
        const val PARTIAL_MS = 800.0
        /** 0.3초보다 짧은 소리는 버린다, 30초에서 자른다 */
        const val MIN_BYTES = 16_000 * 2 * 3 / 10
        const val MAX_BYTES = 16_000 * 2 * 30

        /** PCM16 LE 16kHz 모노 → WAV */
        fun wav(pcm: ByteArray): ByteArray {
            val out = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            out.put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return out.array()
        }
    }
}

/** 소리 크기로 말 시작·끝을 찾는다 (주변 소음에 맞춰 기준을 올린다). 답하는 중에는 기준을 높인다(스피커 소리) */
internal class Endpointer {
    enum class Event { START, END, NONE }

    private var noise = 0.01
    private var speaking = false
    private var speechMs = 0.0
    private var silenceMs = 0.0
    private var loudMs = 0.0
    private val recent = ArrayDeque<ByteArray>()

    fun feed(pcm: ByteArray, replying: Boolean): Event {
        val samples = pcm.size / 2
        if (samples == 0) return Event.NONE
        var sum = 0.0
        for (i in 0 until samples) {
            val v = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / samples)
        val ms = samples / VoiceSession.SAMPLE_RATE * 1000
        val threshold = maxOf(MIN_THRESHOLD, noise * 3) * if (replying) BARGE_FACTOR else 1.0
        if (!speaking) {
            loudMs = if (rms > threshold) loudMs + ms else 0.0
            if (loudMs >= START_MS) {
                speaking = true; speechMs = loudMs; silenceMs = 0.0; loudMs = 0.0
                return Event.START
            }
            if (rms <= threshold && !replying) noise = noise * 0.95 + rms * 0.05
            recent.addLast(pcm)
            while (recent.size > PRE_ROLL) recent.removeFirst()
            return Event.NONE
        }
        if (rms > threshold) { speechMs += ms; silenceMs = 0.0 } else silenceMs += ms
        if (silenceMs >= END_SILENCE_MS) { speaking = false; recent.clear(); return Event.END }
        return Event.NONE
    }

    /** 말 시작 직전 소리 (첫 소리를 잃지 않게) */
    fun preRoll(): List<ByteArray> = recent.toList().also { recent.clear() }

    private companion object {
        const val MIN_THRESHOLD = 0.012
        const val START_MS = 120.0
        const val END_SILENCE_MS = 700.0
        const val BARGE_FACTOR = 2.5
        const val PRE_ROLL = 8
    }
}
