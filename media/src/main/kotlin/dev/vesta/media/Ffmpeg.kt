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
 *
 * 받은 파일은 믿지 않는다: 파일 앞부분으로 형식을 확인해 그 형식(-f)으로만 읽고, 로컬 파일 외 프로토콜은 막는다
 * (재생 목록 형식이 다른 파일·주소를 읽게 하는 공격 방지). ffmpeg 오류 원문은 로그에만 남긴다.
 */
class Ffmpeg(private val binary: String, private val timeoutSeconds: Long = 120) {
    /** 음성 인식용 16kHz 모노 16비트 WAV. [maxSeconds]보다 길면 앞부분만 */
    suspend fun toWav16k(audio: ByteArray, maxSeconds: Int): ByteArray {
        val format = sniff(audio) ?: throw MediaException("읽을 수 없는 소리 파일입니다 (ogg·webm·m4a·mp3·wav·aiff·flac)")
        return convert(audio, format, listOf("-t", maxSeconds.toString(), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav"), "wav")
    }

    /** 합성 음성(aiff 등) → 웹(m4a AAC)·Telegram 음성 메시지(ogg Opus) */
    suspend fun encode(audio: ByteArray, format: AudioFormat): ByteArray = when (format) {
        AudioFormat.M4A -> convert(audio, sniff(audio) ?: "aiff", listOf("-c:a", "aac", "-b:a", "64k", "-f", "mp4"), "m4a")
        AudioFormat.OGG_OPUS -> convert(audio, sniff(audio) ?: "aiff", listOf("-c:a", "libopus", "-b:a", "32k", "-ac", "1", "-f", "ogg"), "ogg")
    }

    /** [format]은 ffmpeg 입력 형식(demuxer) 이름. 확장자도 같게 둔다 */
    private suspend fun convert(input: ByteArray, format: String, args: List<String>, outExt: String): ByteArray = withContext(Dispatchers.IO) {
        val source = Files.createTempFile("vesta-in", ".$format")
        val target = Files.createTempFile("vesta-out", ".$outExt")
        val log = Files.createTempFile("vesta-ffmpeg", ".log")
        try {
            source.writeBytes(input)
            run(
                listOf(binary, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-protocol_whitelist", "file", "-f", format, "-i", source.toString()) +
                    args + target.toString(),
                log,
            )
            target.readBytes().also { if (it.isEmpty()) throw MediaException("소리를 바꾸지 못했습니다") }
        } finally {
            source.deleteIfExists()
            target.deleteIfExists()
            log.deleteIfExists()
        }
    }

    /** 출력은 파일로 받는다: 파이프를 읽느라 시간 제한이 듣지 않는 일이 없게 */
    private fun run(command: List<String>, log: Path) {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        } catch (e: java.io.IOException) {
            throw MediaException("ffmpeg를 실행하지 못했습니다 ($binary). brew install ffmpeg", e)
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw MediaException("소리 변환이 너무 오래 걸립니다")
        }
        if (process.exitValue() != 0) {
            logger.warn { "ffmpeg 실패: ${runCatching { Files.readString(log) }.getOrDefault("").take(500)}" }
            throw MediaException("읽을 수 없는 소리 파일입니다")
        }
    }

    companion object {
        private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

        fun available(binary: String) = Path.of(binary).toFile().canExecute()

        /** 파일 앞부분으로 소리 형식(ffmpeg demuxer 이름)을 고른다. 모르는 형식이면 null */
        fun sniff(bytes: ByteArray): String? {
            fun at(offset: Int, vararg b: Int) = bytes.size >= offset + b.size && b.indices.all { bytes[offset + it] == b[it].toByte() }
            fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
            return when {
                ascii(0, "OggS") -> "ogg"
                at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "matroska" // webm
                ascii(4, "ftyp") -> "mov" // mp4·m4a
                ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
                ascii(0, "FORM") && (ascii(8, "AIFF") || ascii(8, "AIFC")) -> "aiff"
                ascii(0, "fLaC") -> "flac"
                ascii(0, "ID3") || (bytes.size > 1 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 && (bytes[1].toInt() and 0x06) != 0) -> "mp3"
                bytes.size > 1 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xF6) == 0xF0 -> "aac" // ADTS
                else -> null
            }
        }
    }
}
