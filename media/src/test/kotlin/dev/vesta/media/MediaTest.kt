package dev.vesta.media

import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.ImageInput
import dev.vesta.core.media.MediaException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode

private const val FFMPEG = "/opt/homebrew/bin/ffmpeg"

class MediaTest : FunSpec({
    test("읽어 주기용 글: 코드·표·출처·주소·서식은 빼고 길면 문장 끝에서 자른다") {
        val text = SpeechText.prepare(
            """
            ## 결론
            **메모리**가 부족합니다 [1]. 자세한 내용은 [문서](https://example.com)를 보세요 https://a.com/x
            ```kotlin
            val x = 1
            ```
            | a | b |
            |---|---|
            | 1 | 2 |
            - `-Xmx`를 늘리세요.
            ※ 실행하지 않은 작업: 없음
            """.trimIndent(),
            1_000,
        )
        text shouldBe "결론\n메모리가 부족합니다. 자세한 내용은 문서를 보세요\n(코드 생략)\n(표 생략)\n-Xmx를 늘리세요."
        SpeechText.prepare("가나다라마바사입니다. ".repeat(20), 60) shouldEndWith "입니다.\n이하 생략."
    }

    test("이미지 형식은 파일 앞부분으로 확인한다") {
        ImageInput.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)) shouldBe "image/png"
        ImageInput.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) shouldBe "image/jpeg"
        ImageInput.sniff("RIFF0000WEBPVP8 ".toByteArray()) shouldBe "image/webp"
        ImageInput.sniff("<svg onload=alert(1)>".toByteArray()) shouldBe null
    }

    test("ffmpeg: 합성 음성을 16kHz WAV·ogg·m4a로").config(enabled = Ffmpeg.available(FFMPEG)) {
        val ffmpeg = Ffmpeg(FFMPEG)
        val aiff = SayTextToSpeech("Yuna", ffmpeg, 100).synthesize("안녕하세요", AudioFormat.M4A)
        String(aiff, 4, 4) shouldBe "ftyp"
        val ogg = ffmpeg.encode(ffmpeg.toWav16k(aiff, 10), AudioFormat.OGG_OPUS)
        String(ogg, 0, 4) shouldBe "OggS"
        val wav = ffmpeg.toWav16k(ogg, 1)
        String(wav, 0, 4) shouldBe "RIFF"
        shouldThrow<MediaException> { ffmpeg.toWav16k("not audio".toByteArray(), 5) }.message shouldContain "읽을 수 없는"
    }

    test("워커 호출: WAV로 바꿔 보내고 오류는 알기 쉬운 문장으로").config(enabled = Ffmpeg.available(FFMPEG)) {
        val ffmpeg = Ffmpeg(FFMPEG)
        val audio = SayTextToSpeech("Yuna", ffmpeg, 100).synthesize("테스트", AudioFormat.OGG_OPUS)
        val requests = mutableListOf<HttpRequestData>()
        val ok = WorkerSpeechToText("http://w", HttpClient(MockEngine { r ->
            requests += r
            respond("""{"text":" 테스트 ","language":"ko"}""")
        }), ffmpeg, 60)
        ok.transcribe(audio) shouldBe "테스트"
        requests.single().url.toString() shouldBe "http://w/stt?language=ko"
        val body = (requests.single().body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
        String(body, 0, 4) shouldBe "RIFF"

        val failing = WorkerSpeechToText("http://w", HttpClient(MockEngine { respond("""{"error":"bad audio"}""", HttpStatusCode.BadRequest) }), ffmpeg, 60)
        shouldThrow<MediaException> { failing.transcribe(audio) }.message shouldContain "bad audio"
        val down = WorkerSpeechToText("http://w", HttpClient(MockEngine { throw java.net.ConnectException("refused") }), ffmpeg, 60)
        shouldThrow<MediaException> { down.transcribe(audio) }.message shouldNotContain "refused"
    }
})
