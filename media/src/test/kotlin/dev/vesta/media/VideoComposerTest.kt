package dev.vesta.media

import dev.vesta.core.media.Motion
import dev.vesta.core.media.VideoScene
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private const val FFMPEG = "/opt/homebrew/bin/ffmpeg"
private const val FFPROBE = "/opt/homebrew/bin/ffprobe"

private fun png(color: Color, w: Int, h: Int): ByteArray {
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply { this.color = color; fillRect(0, 0, w, h); dispose() }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/** 1초짜리 무음 WAV (16비트 모노 22.05kHz) */
private fun silence(seconds: Double): ByteArray {
    val samples = (22_050 * seconds).toInt()
    val data = samples * 2
    return java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray()); put("fmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(22_050); putInt(22_050 * 2); putShort(2); putShort(16); put("data".toByteArray()); putInt(data)
    }.array()
}

private fun probe(file: java.nio.file.Path, vararg args: String): String {
    val p = ProcessBuilder(listOf(FFPROBE, "-v", "error") + args + file.toString()).redirectErrorStream(true).start()
    p.waitFor(30, TimeUnit.SECONDS)
    return p.inputStream.bufferedReader().readText().trim()
}

class VideoComposerTest : FunSpec({
    test("자막 줄 나누기: 낱말 단위, 긴 낱말은 글자 단위, 세 줄 넘으면 줄임표") {
        val measure = { s: String -> s.length * 10 }
        SubtitleRenderer.wrap("가나다 라마바 사아자", measure, 70) shouldBe listOf("가나다 라마바", "사아자")
        SubtitleRenderer.wrap("가나다라마바사아자차카", measure, 50) shouldBe listOf("가나다라마", "바사아자차", "카")
        val long = SubtitleRenderer.wrap("하나 둘 셋 넷 다섯 여섯 일곱 여덟", measure, 40)
        long.size shouldBe 3
        long.last() shouldContain "…"
        long.all { measure(it) <= 40 } shouldBe true
    }

    test("자막·끝 화면은 영상 크기의 PNG") {
        val sub = ImageIO.read(SubtitleRenderer.subtitle("오늘은 맑고 따뜻합니다", 640, 360).inputStream())
        (sub.width to sub.height) shouldBe (640 to 360)
        sub.colorModel.hasAlpha() shouldBe true
        ImageIO.read(SubtitleRenderer.endCard("우리 동네 맛집", "AI 생성 · Vesta", 640, 360).inputStream()).getRGB(5, 5) shouldBe Color.BLACK.rgb
    }

    test("장면 두 개 + 끝 화면을 이어 mp4로 (내레이션 길이에 맞춤, AI 생성 메타데이터)").config(enabled = Files.isExecutable(java.nio.file.Path.of(FFMPEG))) {
        val composer = FfmpegVideoComposer(Ffmpeg(FFMPEG), fps = 24, minSceneSeconds = 1.0, endCardSeconds = 1.0)
        val done = mutableListOf<Int>()
        val mp4 = composer.compose(
            listOf(
                VideoScene(png(Color.RED, 320, 180), silence(1.5), "첫 장면 자막입니다", Motion.ZOOM_IN),
                VideoScene(png(Color.BLUE, 320, 180), null, "두 번째 장면", Motion.PAN_RIGHT),
            ),
            320, 180, "시험 영상", onScene = { done += it },
        )
        done shouldBe listOf(1, 2)
        val file = Files.createTempFile("video", ".mp4").also { Files.write(it, mp4) }
        // 1.5 + 0.6 여유, 1.0(최소), 끝 화면 1.0 → 약 4.1초
        probe(file, "-show_entries", "format=duration", "-of", "default=nw=1:nk=1").toDouble().shouldBeBetween(3.8, 4.5, 0.0)
        probe(file, "-select_streams", "v:0", "-show_entries", "stream=width,height,codec_name", "-of", "csv=p=0") shouldBe "h264,320,180"
        probe(file, "-show_entries", "format_tags=comment", "-of", "default=nw=1:nk=1") shouldBe "AI 생성 (Vesta)"
        mp4.size shouldBeLessThanOrEqual 5_000_000
    }
})
