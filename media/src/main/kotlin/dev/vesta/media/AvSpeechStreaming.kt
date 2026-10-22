package dev.vesta.media

import dev.vesta.core.media.SpeechStream
import dev.vesta.core.media.StreamingSpeech
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * macOS 음성 합성 스트리밍 (PROJECT.md V-11, M19). `native/vesta-speak`(Swift, AVSpeechSynthesizer.write)를 답변마다 하나 띄워
 * 표준 입력으로 문장을 한 줄씩 넣고, 표준 출력의 PCM을 [StreamingSpeech.open]의 콜백으로 넘긴다.
 * 글은 인자가 아니라 표준 입력으로 넘긴다 (`-`로 시작하는 글이 옵션으로 읽히지 않게)
 */
class AvSpeechStreaming(private val binary: Path, private val voice: String, private val maxChars: Int = 4_000) : StreamingSpeech {
    override fun open(onPcm: (ByteArray) -> Unit): SpeechStream {
        val process = ProcessBuilder(binary.toString(), voice).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val reader = Thread({
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        val n = input.readNBytes(buffer, 0, CHUNK)
                        if (n <= 0) break
                        onPcm(buffer.copyOf(n))
                        if (n < CHUNK) break
                    }
                }
            }.onFailure { log.debug { "이어 말하기 읽기 끝: ${it.message}" } }
        }, "vesta-speak").apply { isDaemon = true; start() }
        val stdin = process.outputStream.bufferedWriter(Charsets.UTF_8)
        return object : SpeechStream {
            override fun say(text: String) {
                val line = SpeechText.prepare(text, maxChars).replace(Regex("\\s+"), " ").trim()
                if (line.isEmpty() || !process.isAlive) return
                runCatching { stdin.write(line); stdin.newLine(); stdin.flush() }
            }

            override fun finish() { runCatching { stdin.close() } }

            override suspend fun await() = runInterruptible(Dispatchers.IO) { reader.join() }

            override fun close() {
                runCatching { stdin.close() }
                process.destroyForcibly()
            }
        }
    }

    companion object {
        /** 0.1초 분량씩 넘긴다 */
        private const val CHUNK = 48_000 * 4 / 10

        fun available(binary: Path) = Files.isExecutable(binary)
    }
}
