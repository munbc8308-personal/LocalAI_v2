package dev.vesta.channels.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException

private const val TOKEN = "123456:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

class HttpTelegramApiTest : FunSpec({
    test("요청 형식과 응답 해석") {
        val requests = mutableListOf<HttpRequestData>()
        val api = HttpTelegramApi(TOKEN, HttpClient(MockEngine { request ->
            requests += request
            respond("""{"ok":true,"result":{"message_id":7,"chat":{"id":42,"type":"private"},"date":1,"text":"x","extra":1}}""")
        }))
        api.sendMessage(42, "<b>x</b>", html = true, keyboard = TgKeyboard(listOf(listOf(TgButton("승인", "ap:r1:a"))))).messageId shouldBe 7
        requests.single().url.toString() shouldBe "https://api.telegram.org/bot$TOKEN/sendMessage"
        val body = (requests.single().body as TextContent).text
        body shouldContain "\"parse_mode\":\"HTML\""
        body shouldContain "\"inline_keyboard\":[[{\"text\":\"승인\",\"callback_data\":\"ap:r1:a\"}]]"
    }

    test("Bot API 오류는 코드와 재시도 시간을 담는다") {
        val api = HttpTelegramApi(TOKEN, HttpClient(MockEngine {
            respond("""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 3","parameters":{"retry_after":3}}""", HttpStatusCode.TooManyRequests)
        }))
        val e = shouldThrow<TelegramException> { api.sendMessage(1, "x") }
        e.code shouldBe 429
        e.retryAfterSeconds shouldBe 3
    }

    test("네트워크 오류 메시지에서 토큰을 지운다") {
        val api = HttpTelegramApi(TOKEN, HttpClient(MockEngine { request -> throw IOException("connect failed: ${request.url}") }))
        val e = shouldThrow<IOException> { api.getMe() }
        e.message!! shouldNotContain TOKEN
        e.message!! shouldContain "<token>"
    }

    test("토큰 형식") {
        HttpTelegramApi.looksLikeToken(TOKEN) shouldBe true
        HttpTelegramApi.looksLikeToken("abc") shouldBe false
        HttpTelegramApi.looksLikeToken("$TOKEN\nadd-generic-password") shouldBe false
    }
})
