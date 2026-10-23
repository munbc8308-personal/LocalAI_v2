package dev.vesta.media

import dev.vesta.core.media.ClipGenerator
import dev.vesta.core.media.MediaException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

private val log = KotlinLogging.logger {}

/**
 * 생성형 짧은 클립 (PROJECT.md G-03): mlx-video(MLX, Apple Silicon)의 Wan2.2 TI2V로 장면 그림을 몇 초짜리 영상으로 움직인다.
 * `~/.vesta/video`(scripts/install-vesta.sh가 고정 커밋으로 만든다)의 uv 환경을 자식 프로세스로 부른다. 프롬프트·경로는 인자 목록으로 넘겨 셸을 거치지 않는다.
 * 하나 만드는 데 오래 걸리므로(측정은 PROJECT.md 13.32) 시간 제한을 두고, 실패하면 [MediaException] (부르는 쪽이 그림으로 대신한다)
 */
class MlxVideoClipGenerator(
    private val uv: String,
    private val projectDir: Path,
    private val modelDir: Path,
    override val model: String = "Wan2.2-TI2V-5B",
    private val steps: Int? = null,
    private val fps: Int = 24,
    private val timeoutSeconds: Long = 900,
) : ClipGenerator {
    override suspend fun animate(image: ByteArray, prompt: String, width: Int, height: Int, seconds: Double): ByteArray = runInterruptible(Dispatchers.IO) {
        if (!Files.isDirectory(modelDir)) throw MediaException("움직이는 클립 모델이 없습니다 ($modelDir)")
        val dir = Files.createTempDirectory("vesta-clip")
        try {
            val input = dir.resolve("scene.png").also { it.writeBytes(image) }
            val output = dir.resolve("clip.mp4")
            val log = dir.resolve("clip.log")
            val command = listOf(
                uv, "run", "--project", projectDir.toString(), "--frozen", "python", "-m", "mlx_video.models.wan_2.generate",
                "--model-dir", modelDir.toString(), "--prompt", prompt.take(1_000), "--image", input.toString(),
                "--width", align(width).toString(), "--height", align(height).toString(), "--num-frames", frames(seconds).toString(),
                "--output-path", output.toString(), "--seed", "42",
            ) + (steps?.let { listOf("--steps", it.toString()) } ?: emptyList())
            val process = ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw MediaException("움직이는 클립이 너무 오래 걸립니다")
            }
            if (process.exitValue() != 0 || !Files.exists(output)) {
                logger.warn { "mlx-video 실패: ${runCatching { Files.readAllLines(log).takeLast(5).joinToString(" | ") }.getOrDefault("")}" }
                throw MediaException("움직이는 클립을 만들지 못했습니다")
            }
            output.readBytes()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        private val logger = log

        /** Wan은 프레임 수가 4n+1 */
        fun frames(seconds: Double, fps: Int = 24): Int = ((seconds * fps).toInt() / 4) * 4 + 1

        /** Wan2.2 VAE(16) × 패치(2) = 32의 배수 */
        fun align(pixels: Int): Int = (pixels / 32).coerceAtLeast(1) * 32
    }
}
