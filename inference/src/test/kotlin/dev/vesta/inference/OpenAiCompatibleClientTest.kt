package dev.vesta.inference

import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.Usage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList

class OpenAiCompatibleClientTest : FunSpec({
    fun client(status: HttpStatusCode = HttpStatusCode.OK, contentType: String = "application/json", body: (String) -> String) =
        OpenAiCompatibleClient(
            "http://engine/v1",
            HttpClients.local(10, MockEngine { req ->
                respond(body(req.url.encodedPath), status, headersOf(HttpHeaders.ContentType, contentType))
            }),
        )

    test("스트리밍 응답을 조각과 사용량으로 나눈다") {
        val sse = """
            data: {"choices":[{"delta":{"role":"assistant","content":"안녕"}}]}

            data: {"choices":[{"delta":{"content":"하세요"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2}}

            data: [DONE]

        """.trimIndent()
        val chunks = client(contentType = "text/event-stream") { sse }
            .chat(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "hi"))))
            .toList()
        chunks shouldContainExactly listOf(
            ChatChunk.Delta("안녕"),
            ChatChunk.Delta("하세요"),
            ChatChunk.Done("stop", Usage(5, 2)),
        )
    }

    test("모델 목록") {
        val models = client { """{"object":"list","data":[{"id":"a","owned_by":"library"},{"id":"b"}]}""" }.listModels()
        models.map { it.id } shouldBe listOf("a", "b")
    }

    test("임베딩은 index 순서로 돌려준다") {
        val result = client {
            """{"data":[{"index":1,"embedding":[0.5]},{"index":0,"embedding":[0.1,0.2]}]}"""
        }.embed("e", listOf("x", "y"))
        result.map { it.toList() } shouldBe listOf(listOf(0.1f, 0.2f), listOf(0.5f))
    }

    test("엔진 오류는 상태 코드와 함께 예외") {
        val error = shouldThrow<InferenceException> {
            client(HttpStatusCode.NotFound) { """{"error":"model not found"}""" }.listModels()
        }
        error.message shouldContain "404"
        error.message shouldContain "model not found"
    }

    test("SSE 파서는 data 줄만 꺼낸다") {
        SseParser.parse("data: {}") shouldBe "{}"
        SseParser.parse(": keepalive") shouldBe null
        SseParser.parse("") shouldBe null
    }
})
