package dev.vesta.webui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.w3c.files.Blob
import kotlin.js.json
import kotlin.math.sqrt

enum class VoicePhase { OFF, LISTENING, HEARING, THINKING, SPEAKING }

/**
 * 음성 대화 모드 (PROJECT.md V-09, M18). 손을 대지 않고 말 → 인식 → 답 → 읽어 주기를 되풀이한다.
 * - 마이크 소리를 직접 받아(ScriptProcessor) 말이 시작·끝나는 때를 소리 크기로 찾는다 (주변 소음에 맞춰 기준을 올린다)
 * - 말이 끝나면(0.9초 조용) 16kHz WAV로 만들어 `/api/stt`로 보내고, 인식한 글을 바로 질문으로 보낸다
 * - 답이 오는 대로 문장 단위로 `/api/speech`에 맡겨 순서대로 읽는다 (다음 문장은 읽는 동안 미리 만든다)
 * - 생각하는 동안에는 마이크 소리를 쓰지 않는다. 읽는 동안에는 들으며, 사용자가 말하면 바로 끊고 그 말부터 받는다 (M37 끼어들기). 눌러도 끊는다
 * 마이크는 HTTPS(Tailscale)나 localhost에서만 열린다
 */
class VoiceConversation(private val scope: CoroutineScope, private val chat: ChatController) {
    var phase by mutableStateOf(VoicePhase.OFF)
    /** 지금 마이크 소리 크기 (0~1, 표시용) */
    var level by mutableStateOf(0.0)
    var error by mutableStateOf<String?>(null)
    /** 마지막으로 알아들은 말 */
    var heard by mutableStateOf<String?>(null)

    private var context: dynamic = null
    private var stream: dynamic = null
    private var source: dynamic = null
    private var processor: dynamic = null
    private var sampleRate = 48_000.0

    private val utterance = mutableListOf<FloatArray>()
    private val preRoll = ArrayDeque<FloatArray>()
    private var noise = 0.01
    /** 읽는 중 사용자 목소리가 이어진 시간 (끼어들기) */
    private var bargeMs = 0.0
    private var speechMs = 0.0
    private var silenceMs = 0.0
    private var totalMs = 0.0

    private var work: Job? = null
    private var audio: dynamic = null
    private var audioDone: CompletableDeferred<Unit>? = null

    val active get() = phase != VoicePhase.OFF

    suspend fun start() {
        if (active) return
        error = null
        val media = window.navigator.asDynamic().mediaDevices ?: throw IllegalStateException("이 브라우저(또는 HTTP 주소)에서는 마이크를 쓸 수 없습니다")
        val constraints = json("audio" to json("echoCancellation" to true, "noiseSuppression" to true, "autoGainControl" to true))
        val opened: dynamic = (media.getUserMedia(constraints) as kotlin.js.Promise<dynamic>).await()
        stream = opened
        val created: dynamic = js("new (window.AudioContext || window.webkitAudioContext)()")
        context = created
        sampleRate = (created.sampleRate as Number).toDouble()
        val input: dynamic = created.createMediaStreamSource(opened)
        val node: dynamic = created.createScriptProcessor(4096, 1, 1)
        node.onaudioprocess = { event: dynamic -> onFrame(event.inputBuffer.getChannelData(0)) }
        input.connect(node)
        node.connect(created.destination)
        source = input
        processor = node
        reset()
        phase = VoicePhase.LISTENING
    }

    fun stop() {
        work?.cancel()
        work = null
        stopAudio()
        runCatching { processor?.disconnect(); source?.disconnect() }
        runCatching { context?.close() }
        stream?.getTracks()?.forEach { track: dynamic -> track.stop() }
        context = null; stream = null; source = null; processor = null
        level = 0.0
        phase = VoicePhase.OFF
    }

    /** 읽는 중이면 끊고(답변 생성도 멈춘다) 다시 듣는다 */
    fun interrupt() {
        if (phase != VoicePhase.SPEAKING && phase != VoicePhase.THINKING) return
        work?.cancel()
        work = null
        stopAudio()
        if (chat.busy) chat.stop()
        listen()
    }

    private fun listen() {
        reset()
        phase = VoicePhase.LISTENING
    }

    private fun reset() {
        utterance.clear()
        preRoll.clear()
        speechMs = 0.0; silenceMs = 0.0; totalMs = 0.0
    }

    private fun onFrame(data: dynamic) {
        val length = (data.length as Number).toInt()
        val frame = FloatArray(length) { (data[it] as Number).toFloat() }
        var sum = 0.0
        for (v in frame) sum += v * v
        val rms = sqrt(sum / maxOf(1, length))
        level = minOf(1.0, rms * 8)
        val ms = length / sampleRate * 1000
        val threshold = maxOf(MIN_THRESHOLD, noise * 3)
        // 끼어들기 (M37 RV-05): 읽는 중에도 듣는다. 스피커 소리는 브라우저 에코 제거가 빼지만 남을 수 있어 기준을 높이고, 잠깐 이어질 때만 끊는다.
        // 끊은 뒤에는 그동안 들은 소리부터 이어서 받는다 (첫 말을 잃지 않게)
        if (phase == VoicePhase.SPEAKING) {
            preRoll.addLast(frame)
            while (preRoll.size > BARGE_FRAMES) preRoll.removeFirst()
            bargeMs = if (rms > threshold * BARGE_FACTOR) bargeMs + ms else 0.0
            if (bargeMs >= BARGE_MS) {
                val heard = preRoll.toList()
                interrupt()
                utterance.addAll(heard)
                speechMs = bargeMs; silenceMs = 0.0; totalMs = bargeMs
                bargeMs = 0.0
                phase = VoicePhase.HEARING
            }
            return
        }
        if (phase != VoicePhase.LISTENING && phase != VoicePhase.HEARING) return
        when (phase) {
            VoicePhase.LISTENING -> if (rms > threshold) {
                utterance.addAll(preRoll)
                utterance += frame
                speechMs = ms; silenceMs = 0.0; totalMs = ms
                phase = VoicePhase.HEARING
            } else {
                noise = noise * 0.95 + rms * 0.05
                preRoll.addLast(frame)
                while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            }
            else -> {
                utterance += frame
                totalMs += ms
                if (rms > threshold) { speechMs += ms; silenceMs = 0.0 } else silenceMs += ms
                if (silenceMs >= END_SILENCE_MS || totalMs >= MAX_UTTERANCE_MS) finish()
            }
        }
    }

    private fun finish() {
        if (speechMs < MIN_SPEECH_MS) { listen(); return }
        val wav = Wav.encode(utterance.toList(), sampleRate, TARGET_RATE)
        utterance.clear()
        phase = VoicePhase.THINKING
        work = scope.launch {
            try {
                val text = MediaApi.transcribe(wav).trim()
                if (text.isBlank()) { listen(); return@launch }
                heard = text
                val turn = chat.send(text, spoken = true) ?: run { listen(); return@launch }
                speak(turn)
                listen()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message
                listen()
            }
        }
    }

    /** 답변이 오는 대로 문장 단위로 읽는다. 만들기는 미리(병렬), 읽기는 순서대로 */
    private suspend fun speak(turn: Turn) = kotlinx.coroutines.coroutineScope {
        val queue = Channel<Deferred<String?>>(Channel.UNLIMITED)
        launch {
            var spoken = 0
            while (true) {
                val text = turn.answer
                val finished = !chat.busy
                val end = SpeechText.cut(text, spoken, finished)
                if (end > spoken) {
                    val piece = SpeechText.clean(text.substring(spoken, end))
                    spoken = end
                    if (piece.isNotBlank()) queue.send(async { runCatching { MediaApi.speech(piece) }.getOrNull() })
                } else if (finished) break
                else delay(150)
            }
            queue.close()
        }
        for (pending in queue) {
            val url = pending.await() ?: continue
            phase = VoicePhase.SPEAKING
            play(url)
        }
    }

    private suspend fun play(url: String) {
        val done = CompletableDeferred<Unit>()
        audioDone = done
        val player: dynamic = js("new Audio(url)")
        player.onended = { _: dynamic -> done.complete(Unit) }
        player.onerror = { _: dynamic -> done.complete(Unit) }
        audio = player
        try {
            (player.play() as kotlin.js.Promise<dynamic>).await()
            done.await()
        } finally {
            audio = null
            org.w3c.dom.url.URL.revokeObjectURL(url)
        }
    }

    private fun stopAudio() {
        audio?.pause()
        audio = null
        audioDone?.complete(Unit)
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val MIN_THRESHOLD = 0.012
        const val PRE_ROLL_FRAMES = 2
        const val END_SILENCE_MS = 700.0
        /** 끼어들기: 평소 기준의 몇 배 소리가 얼마나 이어져야 끊는지, 그때 앞 소리 몇 조각을 살리는지 */
        const val BARGE_FACTOR = 2.5
        const val BARGE_MS = 250.0
        const val BARGE_FRAMES = 6
        const val MIN_SPEECH_MS = 300.0
        const val MAX_UTTERANCE_MS = 30_000.0
    }
}

/** 읽어 줄 글 다듬기 */
object SpeechText {
    private val END = Regex("[.!?。…]+[\"')\\]]*(\\s|$)|\\n+")

    /**
     * [from]부터 읽을 수 있는 데까지의 끝 위치. 문장이 끝난 곳까지, 너무 짧으면(8자 미만) 다음 문장과 붙인다.
     * [finished]면 남은 글 전부. 코드 블록 안이면 블록이 닫힐 때까지 기다린다
     */
    fun cut(text: String, from: Int, finished: Boolean): Int {
        if (finished) return text.length
        val all = text.substring(from)
        // 닫히지 않은 코드 블록이 있으면 그 앞까지만 본다
        val rest = if (all.split("```").size % 2 == 0) all.substring(0, all.lastIndexOf("```")) else all
        var end = -1
        for (m in END.findAll(rest)) {
            val candidate = m.range.last + 1
            if (rest.substring(0, candidate).trim().length >= 8) { end = candidate; break }
        }
        return if (end < 0) from else from + end
    }

    /** 코드 블록·표·링크·출처 번호·마크다운 기호를 읽지 않는다 */
    fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " 코드는 화면에 있습니다. ")
        .replace(Regex("^\\|.*\\|\\s*$", RegexOption.MULTILINE), " ")
        .replace(Regex("\\[(\\d+)\\]"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), " 링크 ")
        .replace(Regex("[*_`#>~]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** 모노 16비트 WAV (음성 인식용). [rate]로 낮춘다 (구간 평균) */
object Wav {
    fun encode(frames: List<FloatArray>, inputRate: Double, rate: Int): Blob {
        val total = frames.sumOf { it.size }
        val step = inputRate / rate
        val count = (total / step).toInt()
        val samples = FloatArray(total).also { out -> var i = 0; frames.forEach { f -> f.copyInto(out, i); i += f.size } }
        val buffer = ArrayBuffer(44 + count * 2)
        val view = DataView(buffer)
        fun text(at: Int, s: String) = s.forEachIndexed { i, c -> view.setUint8(at + i, c.code.toByte()) }
        text(0, "RIFF"); view.setUint32(4, 36 + count * 2, true); text(8, "WAVE")
        text(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, 1.toShort(), true); view.setUint16(22, 1.toShort(), true)
        view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true); view.setUint16(32, 2.toShort(), true); view.setUint16(34, 16.toShort(), true)
        text(36, "data"); view.setUint32(40, count * 2, true)
        for (i in 0 until count) {
            val start = (i * step).toInt()
            val end = minOf(total, ((i + 1) * step).toInt().coerceAtLeast(start + 1))
            var sum = 0.0
            for (k in start until end) sum += samples[k]
            val v = (sum / (end - start)).coerceIn(-1.0, 1.0)
            view.setInt16(44 + i * 2, (v * 32767).toInt().toShort(), true)
        }
        return Blob(arrayOf(buffer), js("({type: 'audio/wav'})"))
    }
}
