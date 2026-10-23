package dev.vesta.a2a

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.Session
import dev.vesta.core.studio.A2aAgentDef
import dev.vesta.core.tools.ApprovalBroker
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.PendingApproval
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

private val json = headersOf(HttpHeaders.ContentType, "application/json")
private val sse = headersOf(HttpHeaders.ContentType, "text/event-stream")

/** 가짜 DNS: *.example은 바깥, localhost·internal.example은 내부. [rebound]에 든 호스트는 내부로 */
val rebound = mutableSetOf<String>()
private val dns: suspend (String) -> List<java.net.InetAddress> = { host ->
    val internal = host == "localhost" || host == "127.0.0.1" || host == "internal.example" || host in rebound
    listOf(java.net.InetAddress.getByAddress(host, if (internal) byteArrayOf(127, 0, 0, 1) else byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
}

private fun client(handler: suspend (HttpRequestData, String) -> Pair<String, io.ktor.http.Headers>, seen: MutableList<Pair<HttpRequestData, String>> = mutableListOf()) =
    A2aClient(HttpClient(MockEngine { request ->
        val body = runCatching { request.body.toByteArray().decodeToString() }.getOrDefault("")
        seen += request to body
        val (text, headers) = handler(request, body)
        if (text == "404") respond("", HttpStatusCode.NotFound) else respond(text, HttpStatusCode.OK, headers)
    }) { install(HttpTimeout) }, resolve = dns)

private val card10 = """{"name":"Echo","description":"따라 말하기","supportedInterfaces":[{"url":"https://agent.example/grpc","protocolBinding":"GRPC","protocolVersion":"1.0"},{"url":"https://agent.example/a2a/v1","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],
  "capabilities":{"streaming":true},"securitySchemes":{"bearer":{"httpAuthSecurityScheme":{"scheme":"Bearer"}}},"skills":[{"id":"echo","name":"Echo","description":"그대로"}],"version":"2.1"}"""
private val card03 = """{"name":"Old","description":"옛 버전","url":"http://localhost:9999/","protocolVersion":"0.3.0","preferredTransport":"JSONRPC","capabilities":{"streaming":false},"skills":[]}"""

class A2aClientTest : FunSpec({
    test("Agent Card: agent-card.json이 없으면 agent.json, JSON-RPC 연결만 고른다, 1.0·0.3 구분") {
        val seen = mutableListOf<Pair<HttpRequestData, String>>()
        val c = client({ request, _ -> if (request.url.encodedPath == "/.well-known/agent-card.json") "404" to json else card03 to json }, seen)
        val old = c.card("http://localhost:9999")
        seen.map { it.first.url.toString() } shouldBe listOf("http://localhost:9999/.well-known/agent-card.json", "http://localhost:9999/.well-known/agent.json")
        old.protocol shouldBe "0.3"
        old.endpoint shouldBe "http://localhost:9999/"
        old.streaming shouldBe false

        val v1 = A2aClient.parseCard("https://agent.example/.well-known/agent-card.json", Json.parseToJsonElement(card10).jsonObject)
        v1.endpoint shouldBe "https://agent.example/a2a/v1"
        v1.protocol shouldBe "1.0"
        v1.streaming shouldBe true
        v1.auth shouldBe true
        v1.skills shouldBe listOf("Echo — 그대로")
        shouldThrow<A2aException> { A2aClient.parseCard("x", Json.parseToJsonElement("""{"supportedInterfaces":[{"url":"https://a/","protocolBinding":"GRPC"}]}""").jsonObject) }
            .message shouldContain "JSON-RPC"
        shouldThrow<A2aException> { A2aClient.parseCard("x", Json.parseToJsonElement("""{"url":"file:///etc/passwd"}""").jsonObject) }
        shouldThrow<A2aException> { c.card("ftp://x") }
    }

    test("1.0 스트리밍: SendStreamingMessage·A2A-Version·ROLE_USER, 상태는 진행으로, 결과물은 답으로") {
        val seen = mutableListOf<Pair<HttpRequestData, String>>()
        val stream = listOf(
            """{"jsonrpc":"2.0","id":1,"result":{"task":{"id":"t1","contextId":"c1","status":{"state":"TASK_STATE_SUBMITTED"}}}}""",
            """{"jsonrpc":"2.0","id":1,"result":{"statusUpdate":{"taskId":"t1","status":{"state":"TASK_STATE_WORKING","message":{"role":"ROLE_AGENT","parts":[{"text":"찾는 중"}]}}}}}""",
            """{"jsonrpc":"2.0","id":1,"result":{"artifactUpdate":{"taskId":"t1","artifact":{"artifactId":"a1","parts":[{"text":"안녕"}]}}}}""",
            """{"jsonrpc":"2.0","id":1,"result":{"artifactUpdate":{"taskId":"t1","artifact":{"artifactId":"a1","parts":[{"text":"하세요"}]},"append":true}}}""",
            """{"jsonrpc":"2.0","id":1,"result":{"statusUpdate":{"taskId":"t1","status":{"state":"TASK_STATE_COMPLETED"}}}}""",
        ).joinToString("") { "data: $it\n\n" }
        val c = client({ _, _ -> stream to sse }, seen)
        val card = A2aClient.parseCard("x", Json.parseToJsonElement(card10).jsonObject)
        val updates = c.send(card, "인사해 줘", "tok").toList()
        updates shouldBe listOf(
            A2aUpdate.Status("submitted", null),
            A2aUpdate.Status("working", "찾는 중"),
            A2aUpdate.Text("안녕"),
            A2aUpdate.Text("하세요"),
            A2aUpdate.Finished("completed", null),
        )
        val (request, body) = seen.single()
        request.headers["A2A-Version"] shouldBe "1.0"
        request.headers[HttpHeaders.Authorization] shouldBe "Bearer tok"
        val rpc = Json.parseToJsonElement(body).jsonObject
        rpc["method"]!!.jsonPrimitive.content shouldBe "SendStreamingMessage"
        body shouldContain "\"role\":\"ROLE_USER\""
        body shouldContain "\"parts\":[{\"text\":\"인사해 줘\"}]"
    }

    test("0.3 비스트리밍: message/send, kind 구분자, 끝 상태의 메시지를 답으로. JSON-RPC 오류는 예외") {
        val seen = mutableListOf<Pair<HttpRequestData, String>>()
        val c = client({ _, body ->
            if ("오류" in body) """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad"}}""" to json
            else """{"jsonrpc":"2.0","id":1,"result":{"kind":"task","id":"t9","status":{"state":"completed","message":{"kind":"message","role":"agent","parts":[{"kind":"text","text":"다 했어요"}]}}}}""" to json
        }, seen)
        val card = A2aClient.parseCard("x", Json.parseToJsonElement(card03).jsonObject).copy(local = true) // 등록 때 로컬로 확인한 에이전트
        c.send(card, "해 줘", null).toList() shouldBe listOf(A2aUpdate.Text("다 했어요"), A2aUpdate.Finished("completed", "다 했어요"))
        seen.single().second.let { it shouldContain "\"method\":\"message/send\""; it shouldContain "\"kind\":\"text\""; it shouldContain "\"role\":\"user\"" }
        seen.single().first.headers["A2A-Version"] shouldBe null
        shouldThrow<A2aException> { c.send(card, "오류", null).toList() }.message shouldContain "bad"
    }

    test("답 길이 한도: 넘으면 자르고 알린다") {
        val c = A2aClient(HttpClient(MockEngine { respond("""{"jsonrpc":"2.0","id":1,"result":{"message":{"role":"ROLE_AGENT","parts":[{"text":"${"가".repeat(50)}"}]}}}""", HttpStatusCode.OK, json) }) { install(HttpTimeout) }, maxAnswerChars = 10, resolve = dns)
        val card = A2aClient.parseCard("x", Json.parseToJsonElement(card10).jsonObject).copy(streaming = false)
        val text = c.send(card, "길게", null).toList().filterIsInstance<A2aUpdate.Text>().joinToString("") { it.text }
        text shouldContain "가".repeat(10)
        text shouldContain "10자까지만"
    }

    test("A2aAgent: 로컬 데이터를 읽은 흐름에서 온 할 일은 보내기 전에 승인, 거부하면 보내지 않는다") {
        val seen = mutableListOf<Pair<HttpRequestData, String>>()
        val c = client({ _, _ -> """{"jsonrpc":"2.0","id":1,"result":{"message":{"role":"ROLE_AGENT","parts":[{"text":"받음"}]}}}""" to json }, seen)
        val def = A2aAgentDef(UserId(1), "echo", "x", "https://agent.example/a2a/v1", "1.0", "Echo", "따라 말하기")
        val user = UserContext(UserId(1), UserRole.OWNER)
        val session = Session(SessionId("s"), user.userId, null, ChannelType.WEB, null, Instant.EPOCH, Instant.EPOCH)
        val request = AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "회의록 내용: 비밀")))
        class Broker(val decision: ApprovalDecision) : ApprovalBroker {
            var opened = 0
            override fun open(owner: UserContext) = PendingApproval("r${++opened}", owner).also { it.result.complete(decision) }
            override fun resolve(user: UserContext, id: String, decision: ApprovalDecision) = false
            override fun close(id: String) = Unit
        }
        val plain = Broker(ApprovalDecision.Approve)
        A2aAgent(def, c, plain, { null }).handle(request).toList().filterIsInstance<AgentEvent.Token>().single().text shouldBe "받음"
        plain.opened shouldBe 0

        val deny = Broker(ApprovalDecision.Deny("싫음"))
        val events = A2aAgent(def, c, deny, { null }).handle(request.copy(tainted = true)).toList()
        deny.opened shouldBe 1
        events.filterIsInstance<AgentEvent.ApprovalRequested>().single().details.body shouldBe "회의록 내용: 비밀"
        events.filterIsInstance<AgentEvent.Token>().single().text shouldContain "보내지 않았습니다"
        seen.size shouldBe 1 // 거부한 것은 보내지 않았다
    }

    test("보안 검토: 카드가 다른 호스트의 내부 주소로 연결하게 하면 거부, 등록 뒤 내부로 바뀐 주소로는 보내지 않는다, 끝없는 한 줄은 한도에서 멈춘다") {
        val evil = """{"name":"x","supportedInterfaces":[{"url":"http://internal.example:11434/api","protocolBinding":"JSONRPC","protocolVersion":"1.0"}]}"""
        val c = client({ _, _ -> evil to json })
        shouldThrow<A2aException> { c.card("https://agent.example") }.message shouldContain "내부 주소"
        // 사용자가 직접 넣은 로컬 에이전트(카드와 같은 호스트)는 된다
        val own = client({ _, _ -> card03 to json }).card("http://localhost:9999/.well-known/agent.json")
        own.local shouldBe true

        val outside = client({ _, _ -> """{"jsonrpc":"2.0","id":1,"result":{"message":{"parts":[{"text":"ok"}]}}}""" to json })
        val card = A2aClient.parseCard("https://rebind.example/.well-known/agent-card.json", Json.parseToJsonElement(card10.replace("agent.example", "rebind.example")).jsonObject).copy(streaming = false)
        outside.send(card, "hi", null).toList().filterIsInstance<A2aUpdate.Text>().single().text shouldBe "ok"
        rebound += "rebind.example"
        try {
            shouldThrow<A2aException> { outside.send(card, "hi", null).toList() }.message shouldContain "내부 주소"
        } finally { rebound.clear() }

        val huge = A2aClient(HttpClient(MockEngine { respond("data: " + "x".repeat(5_000), HttpStatusCode.OK, sse) }) { install(HttpTimeout) }, maxResponseBytes = 1_000, resolve = dns)
        val streaming = A2aClient.parseCard("x", Json.parseToJsonElement(card10).jsonObject)
        shouldThrow<A2aException> { huge.send(streaming, "hi", null).toList() }.message shouldContain "너무 큽니다"
    }
})
