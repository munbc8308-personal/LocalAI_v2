package dev.vesta.channels.discord

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
import java.io.IOException

/** 형식만 맞춘 가짜 (실제 토큰 모양 문자열은 비밀 검사에 걸려 실행 중에 만든다) */
private val TOKEN = "test".repeat(6) + ".fake." + "x".repeat(27)

class HttpDiscordApiTest : FunSpec({
    test("메시지는 멘션·링크 미리보기를 끄고 버튼 줄을 담는다") {
        val requests = mutableListOf<HttpRequestData>()
        val api = HttpDiscordApi(TOKEN, HttpClient(MockEngine { request ->
            requests += request
            respond("""{"id":"9","channel_id":"dm","content":"x"}""")
        }))
        api.createMessage("dm", "안녕", listOf(listOf(DcButton("승인", "ap:r1:a", 1)))).id shouldBe "9"
        val request = requests.single()
        request.url.toString() shouldBe "https://discord.com/api/v10/channels/dm/messages"
        request.headers["Authorization"] shouldBe "Bot $TOKEN"
        val body = (request.body as TextContent).text
        body shouldContain "\"allowed_mentions\":{\"parse\":[]}"
        body shouldContain "\"flags\":4"
        body shouldContain "{\"type\":2,\"style\":1,\"label\":\"승인\",\"custom_id\":\"ap:r1:a\"}"
    }

    test("429면 알려 준 시간만큼 기다렸다 다시 보낸다") {
        var calls = 0
        val api = HttpDiscordApi(TOKEN, HttpClient(MockEngine {
            if (calls++ == 0) respond("""{"message":"You are being rate limited.","retry_after":0.05,"global":false}""", HttpStatusCode.TooManyRequests)
            else respond("""{"id":"9","channel_id":"dm"}""")
        }))
        api.createMessage("dm", "x").id shouldBe "9"
        calls shouldBe 2
    }

    test("오류 메시지와 첨부 주소 제한") {
        val api = HttpDiscordApi(TOKEN, HttpClient(MockEngine { respond("""{"message":"Missing Access","code":50001}""", HttpStatusCode.Forbidden) }))
        shouldThrow<DiscordException> { api.editMessage("dm", "1", "x") }.code shouldBe 403
        shouldThrow<IOException> { api.download("https://evil.example.com/a.md", 100) }.message shouldContain "Discord 첨부 주소가 아닙니다"
        shouldThrow<IOException> { api.download("http://cdn.discordapp.com/a.md", 100) }
    }

    test("토큰 형식") {
        HttpDiscordApi.looksLikeToken(TOKEN) shouldBe true
        HttpDiscordApi.looksLikeToken("123456:AAAA") shouldBe false
    }
})
