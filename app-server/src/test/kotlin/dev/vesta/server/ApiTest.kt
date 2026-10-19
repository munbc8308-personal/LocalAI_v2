package dev.vesta.server

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.RiskLevel
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.Session
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.session.StoredMessage
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

private class FakeConversation : ConversationService {
    val decisions = mutableListOf<Pair<String, ApprovalDecision>>()
    var lastChannel: ChannelType? = null
    override fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent> = flow {
        lastChannel = channel
        emit(AgentEvent.SessionReady(SessionId("s1"), "제목", true))
        if (input.text == "위험") emit(AgentEvent.ApprovalRequested("r1", "run_shell", RiskLevel.EXEC, ApprovalDetails("셸", "ls"), null))
        emit(AgentEvent.Token("안녕"))
        emit(AgentEvent.Token("하세요"))
        emit(AgentEvent.Done(AgentType.CHAT, "m", null))
    }
    override suspend fun resolveApproval(user: UserContext, requestId: String, decision: ApprovalDecision): Boolean {
        decisions += requestId to decision
        return true
    }
}

private object NoSessions : SessionRepository {
    override suspend fun create(userId: UserId, channel: ChannelType, title: String?) = error("x")
    override suspend fun find(userId: UserId, id: SessionId): Session? = null
    override suspend fun listRecent(userId: UserId, limit: Int) = listOf(Session(SessionId("s1"), userId, "제목", ChannelType.CLI, null, Instant.EPOCH, Instant.EPOCH))
    override suspend fun updateTitle(userId: UserId, id: SessionId, title: String) = Unit
}

private object NoMessages : MessageRepository {
    override suspend fun append(sessionId: SessionId, message: NewMessage): StoredMessage = error("x")
    override suspend fun list(sessionId: SessionId) = emptyList<StoredMessage>()
}

class ApiTest : FunSpec({
    val conversation = FakeConversation()
    val deps = ServerDeps(conversation, NoSessions, NoMessages, UserContext(UserId(1), UserRole.OWNER), token = "secret", version = "t")

    test("health는 토큰 없이, 나머지는 토큰 필요") { testApplication {
        application { vestaApi(deps) }
        client.get("/api/health").bodyAsText() shouldContain "\"status\":\"ok\""
        client.get("/api/sessions").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/sessions") { bearerAuth("wrong") }.status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/sessions") { bearerAuth("secret") }.bodyAsText() shouldContain "\"id\":\"s1\""
    } }

    test("/api/chat은 이벤트를 SSE로 흘려보낸다") { testApplication {
        application { vestaApi(deps) }
        val body = client.post("/api/chat") { bearerAuth("secret"); setBody("""{"text":"hi","channel":"CLI"}""") }.bodyAsText()
        body shouldContain "data: {\"type\":\"session\""
        body shouldContain "\"type\":\"token\",\"text\":\"안녕\""
        body shouldContain "\"type\":\"done\""
        conversation.lastChannel shouldBe ChannelType.CLI
    } }

    test("승인 응답을 대화 엔진에 넘긴다") { testApplication {
        application { vestaApi(deps) }
        client.post("/api/approvals/r9") { bearerAuth("secret"); setBody("""{"decision":"edit","edited":{"command":"ls -la"}}""") }.status shouldBe HttpStatusCode.OK
        (conversation.decisions.last().second as ApprovalDecision.ApproveEdited).editedArguments.toString() shouldContain "ls -la"
    } }

    test("OpenAI 호환: 답을 모아 돌려주고, 승인 요청은 자동 거부한다") { testApplication {
        application { vestaApi(deps) }
        val body = client.post("/v1/chat/completions") { bearerAuth("secret"); setBody("""{"model":"vesta","messages":[{"role":"user","content":"위험"}]}""") }.bodyAsText()
        body shouldContain "\"content\":\"안녕하세요\""
        (conversation.decisions.last().second is ApprovalDecision.Deny) shouldBe true
        val stream = client.post("/v1/chat/completions") { bearerAuth("secret"); setBody("""{"messages":[{"role":"user","content":"hi"}],"stream":true}""") }.bodyAsText()
        stream shouldContain "chat.completion.chunk"
        stream shouldContain "data: [DONE]"
        client.get("/v1/models") { bearerAuth("secret") }.bodyAsText() shouldContain "\"id\":\"vesta\""
    } }
})
