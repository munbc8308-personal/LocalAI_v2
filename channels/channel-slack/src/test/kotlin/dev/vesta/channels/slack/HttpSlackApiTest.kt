package dev.vesta.channels.slack

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException

/** 형식만 맞춘 가짜 (실제 토큰 모양 문자열은 비밀 검사에 걸려 실행 중에 만든다) */
private val BOT = "xoxb-" + "test-".repeat(5)
private val APP = "xapp-" + "fake-".repeat(5)

class HttpSlackApiTest : FunSpec({
    test("토큰별 호출과 응답 해석") {
        val requests = mutableListOf<HttpRequestData>()
        val api = HttpSlackApi(BOT, APP, HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/apps.connections.open" -> respond("""{"ok":true,"url":"wss://wss-primary.slack.com/link/?ticket=x"}""")
                else -> respond("""{"ok":true,"channel":"D1","ts":"1700.0001"}""")
            }
        }))
        api.openConnection() shouldBe "wss://wss-primary.slack.com/link/?ticket=x"
        requests.last().headers["Authorization"] shouldBe "Bearer $APP"
        api.postMessage("D1", "안녕") shouldBe "1700.0001"
        requests.last().headers["Authorization"] shouldBe "Bearer $BOT"
        (requests.last().body as TextContent).text shouldContain "\"unfurl_links\":false"
    }

    test("ok:false는 오류 코드로, 토큰 거부는 멈출 오류로") {
        val api = HttpSlackApi(BOT, APP, HttpClient(MockEngine { respond("""{"ok":false,"error":"invalid_auth"}""") }))
        shouldThrow<SlackException> { api.authTest() }.fatal shouldBe true
    }

    test("429면 Retry-After만큼 기다렸다 다시 보낸다") {
        var calls = 0
        val api = HttpSlackApi(BOT, APP, HttpClient(MockEngine {
            if (calls++ == 0) respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
            else respond("""{"ok":true,"ts":"1"}""")
        }))
        api.postMessage("D1", "x") shouldBe "1"
        calls shouldBe 2
    }

    test("파일은 files.slack.com에서만, 권한이 없어 HTML이 오면 실패") {
        val api = HttpSlackApi(BOT, APP, HttpClient(MockEngine { respond("<html>login</html>", headers = headersOf("Content-Type", "text/html")) }))
        shouldThrow<IOException> { api.download("https://evil.example.com/f", 100) }.message shouldContain "Slack 파일 주소가 아닙니다"
        shouldThrow<IOException> { api.download("https://files.slack.com/files-pri/T/F/a.md", 100) }.message shouldContain "files:read"
    }

    test("토큰 형식") {
        HttpSlackApi.looksLikeBotToken(BOT) shouldBe true
        HttpSlackApi.looksLikeAppToken(APP) shouldBe true
        HttpSlackApi.looksLikeBotToken(APP) shouldBe false
    }
})
