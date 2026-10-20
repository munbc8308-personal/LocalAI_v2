package dev.vesta.media

import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.MediaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * ffmpeg로 소리 형식을 바꾼다 (PROJECT.md V-01: OGG/Opus·WebM·M4A → WAV).
 * m4a처럼 파일 끝에 정보가 있는 형식은 파이프로 못 읽어서 임시 파일을 쓴다. 인자는 목록으로 넘겨 셸을 거치지 않는다.
 */
class Ffmpeg(private val binary: String, private val timeoutSeconds: Long = 120) {
    /** 음성 인식용 16kHz 모노 16비트 WAV. [maxSeconds]보다 길면 앞부분만 */
    suspend fun toWav16k(audio: ByteArray, maxSeconds: Int): ByteArray =
        convert(audio, "in", listOf("-t", maxSeconds.toString(), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav"), "wav")

    /** 합성 음성(aiff 등) → 웹(m4a AAC)·Telegram 음성 메시지(ogg Opus) */
    suspend fun encode(audio: ByteArray, format: AudioFormat): ByteArray = when (format) {
        AudioFormat.M4A -> convert(audio, "aiff", listOf("-c:a", "aac", "-b:a", "64k", "-f", "mp4"), "m4a")
        AudioFormat.OGG_OPUS -> convert(audio, "aiff", listOf("-c:a", "libopus", "-b:a", "32k", "-ac", "1", "-f", "ogg"), "ogg")
    }

    private suspend fun convert(input: ByteArray, inExt: String, args: List<String>, outExt: String): ByteArray = withContext(Dispatchers.IO) {
        val source = Files.createTempFile("vesta-in", ".$inExt")
        val target = Files.createTempFile("vesta-out", ".$outExt")
        try {
            source.writeBytes(input)
            run(listOf(binary, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", source.toString()) + args + target.toString())
            target.readBytes().also { if (it.isEmpty()) throw MediaException("소리를 바꾸지 못했습니다 (빈 결과)") }
        } finally {
            source.deleteIfExists()
            target.deleteIfExists()
        }
    }

    private fun run(command: List<String>) {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw MediaException("ffmpeg를 실행하지 못했습니다 ($binary). brew install ffmpeg", e)
        }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw MediaException("소리 변환이 너무 오래 걸립니다")
        }
        if (process.exitValue() != 0) throw MediaException("읽을 수 없는 소리 파일입니다: ${output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(200)}")
    }

    companion object {
        fun available(binary: String) = Path.of(binary).toFile().canExecute()
    }
}
