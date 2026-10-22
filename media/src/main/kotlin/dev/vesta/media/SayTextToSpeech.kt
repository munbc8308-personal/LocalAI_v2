package dev.vesta.media

import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/**
 * macOS `say`로 읽어 준다 (PROJECT.md V-03 1차: 한국어 음성 Yuna, 추가 메모리 0).
 * 글은 임시 파일로 넘긴다(인자로 넘기면 `-`로 시작하는 글이 옵션으로 읽힐 수 있다).
 */
class SayTextToSpeech(private val voice: String, private val ffmpeg: Ffmpeg, private val maxChars: Int) : TextToSpeech {
    override suspend fun synthesize(text: String, format: AudioFormat): ByteArray = synthesize(text, format, null)

    /** [voice]가 없으면 설정의 목소리. 목소리 이름은 서버가 설치된 음성 목록으로 확인한 것만 온다 */
    override suspend fun synthesize(text: String, format: AudioFormat, voice: String?): ByteArray {
        val chosen = voice?.takeIf { it.isNotBlank() && !it.startsWith("-") } ?: this.voice
        val speech = SpeechText.prepare(text, maxChars)
        if (speech.isBlank()) throw MediaException("읽어 줄 내용이 없습니다")
        val aiff = withContext(Dispatchers.IO) {
            val input = Files.createTempFile("vesta-say", ".txt")
            val output = Files.createTempFile("vesta-say", ".aiff")
            try {
                input.writeText(speech)
                val process = ProcessBuilder("/usr/bin/say", "-v", chosen, "-f", input.toString(), "-o", output.toString())
                    .redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw MediaException("읽어 주기가 너무 오래 걸립니다")
                }
                if (process.exitValue() != 0) throw MediaException("읽어 주기 실패: ${log.take(200)}")
                output.readBytes()
            } finally {
                input.deleteIfExists()
                output.deleteIfExists()
            }
        }
        return ffmpeg.encode(aiff, format)
    }
}
