package dev.vesta.server

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.tools.ToolResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class ClientToolsApiTest : FunSpec({
    val owner = UserContext(UserId(1), UserRole.OWNER)

    test("다른 PC의 작업 폴더 (RC-07): 도구 정의가 있으면 받고, 결과는 요청을 연 사람만 돌려준다") { testApplication {
        val inputs = mutableListOf<UserInput>()
        val conversation = object : dev.vesta.core.conversation.ConversationService {
            override fun send(user: UserContext, channel: dev.vesta.core.ChannelType, sessionId: dev.vesta.core.SessionId?, input: UserInput): Flow<AgentEvent> { inputs += input; return emptyFlow() }
            override suspend fun resolveApproval(user: UserContext, requestId: String, decision: dev.vesta.core.tools.ApprovalDecision) = false
        }
        val broker = dev.vesta.conversation.InMemoryClientToolBroker()
        application { vestaApi(ServerDeps(conversation, EmptySessions, EmptyMessages, owner, token = "secret", version = "t", clientTools = broker)) }

        val tool = """{"name":"run_shell","description":"PowerShell","parameters":"{\"type\":\"object\"}"}"""
        client.post("/api/chat") { bearerAuth("secret"); setBody("""{"text":"빌드해","channel":"CLI","workspace":"C:\\dev\\app","remote":true,"os":"Windows 11","clientTools":[$tool]}""") }.bodyAsText()
        inputs.last().workspace!!.let { ws ->
            ws.remote shouldBe true
            ws.path shouldBe "C:\\dev\\app"
            ws.os shouldBe "Windows 11"
            ws.clientTools.single().name shouldBe "run_shell"
        }
        // 도구 정의 없이 remote만 오면 작업 폴더를 받지 않는다 (이 Mac 폴더로 오인하지 않게)
        client.post("/api/chat") { bearerAuth("secret"); setBody("""{"text":"x","channel":"CLI","workspace":"C:\\dev\\app","remote":true}""") }.bodyAsText()
        inputs.last().workspace shouldBe null

        val pending = broker.open(owner)
        client.post("/api/client-tools/${pending.id}") { bearerAuth("secret"); setBody("""{"output":"PASS","isError":false}""") }.status shouldBe HttpStatusCode.OK
        pending.result.await() shouldBe ToolResult("PASS", false, "PASS")
        client.post("/api/client-tools/${pending.id}") { bearerAuth("secret"); setBody("""{"output":"again"}""") }.status shouldBe HttpStatusCode.NotFound
        // 다른 사용자의 요청에는 답할 수 없다
        val other = broker.open(UserContext(UserId(2), UserRole.MEMBER))
        client.post("/api/client-tools/${other.id}") { bearerAuth("secret"); setBody("""{"output":"x"}""") }.status shouldBe HttpStatusCode.NotFound
    } }
})
