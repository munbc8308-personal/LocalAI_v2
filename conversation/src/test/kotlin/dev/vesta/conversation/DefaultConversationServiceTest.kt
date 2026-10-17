package dev.vesta.conversation

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.agent.UserInput
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.Session
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.session.StoredMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.time.Instant
import java.util.UUID

private class MemorySessions : SessionRepository {
    val items = mutableMapOf<SessionId, Session>()
    override suspend fun create(userId: UserId, channel: ChannelType, title: String?) =
        Session(SessionId(UUID.randomUUID().toString()), userId, title, channel, null, Instant.EPOCH, Instant.EPOCH).also { items[it.id] = it }
    override suspend fun find(userId: UserId, id: SessionId) = items[id]?.takeIf { it.userId == userId }
    override suspend fun listRecent(userId: UserId, limit: Int) = items.values.filter { it.userId == userId }.take(limit)
    override suspend fun updateTitle(userId: UserId, id: SessionId, title: String) = Unit
}

private class MemoryMessages : MessageRepository {
    val items = mutableListOf<StoredMessage>()
    override suspend fun append(sessionId: SessionId, message: NewMessage) =
        StoredMessage(items.size.toLong() + 1, sessionId, message.role, message.content, message.agent, message.model, message.interrupted, Instant.EPOCH)
            .also { items += it }
    override suspend fun list(sessionId: SessionId) = items.filter { it.sessionId == sessionId }
}

private class ScriptedAgent(private val script: suspend kotlinx.coroutines.flow.FlowCollector<AgentEvent>.(AgentRequest) -> Unit) : Agent {
    val requests = mutableListOf<AgentRequest>()
    override val type = AgentType.CHAT
    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        requests += request
        script(request)
    }
}

class DefaultConversationServiceTest : FunSpec({
    val user = UserContext(UserId(1), UserRole.OWNER)

    test("새 세션을 만들고 질문·답변을 저장한다. 다음 턴에는 기록이 넘어간다") {
        val sessions = MemorySessions()
        val messages = MemoryMessages()
        val agent = ScriptedAgent {
            emit(AgentEvent.Token("안녕"))
            emit(AgentEvent.Token("하세요"))
            emit(AgentEvent.Done(AgentType.CHAT, "m", null))
        }
        val service = DefaultConversationService(sessions, messages, mapOf(AgentType.CHAT to agent))

        val first = service.send(user, ChannelType.CLI, null, UserInput("인사해 줘\n둘째 줄")).toList()
        val ready = first.first().shouldBeInstanceOf<AgentEvent.SessionReady>()
        ready.created shouldBe true
        ready.title shouldBe "인사해 줘"

        service.send(user, ChannelType.CLI, ready.sessionId, UserInput("또")).toList()
        messages.items.map { it.role to it.content } shouldBe listOf(
            ChatRole.USER to "인사해 줘\n둘째 줄",
            ChatRole.ASSISTANT to "안녕하세요",
            ChatRole.USER to "또",
            ChatRole.ASSISTANT to "안녕하세요",
        )
        agent.requests.last().history.map { it.content } shouldBe listOf("인사해 줘\n둘째 줄", "안녕하세요", "또")
        messages.items[1].interrupted shouldBe false
    }

    test("중간에 취소해도 생성된 부분은 interrupted로 저장한다") {
        val messages = MemoryMessages()
        val agent = ScriptedAgent {
            emit(AgentEvent.Token("부분"))
            awaitCancellation()
        }
        val service = DefaultConversationService(MemorySessions(), messages, mapOf(AgentType.CHAT to agent))

        // SessionReady, Token 두 개만 받고 수집을 끝낸다 = 사용자 중단
        service.send(user, ChannelType.CLI, null, UserInput("길게 말해 줘")).take(2).toList()
        val saved = messages.items.last()
        saved.content shouldBe "부분"
        saved.interrupted shouldBe true
    }

    test("에이전트 오류는 Error 이벤트로 바뀌고 빈 답변은 저장하지 않는다") {
        val messages = MemoryMessages()
        val agent = ScriptedAgent { error("엔진 연결 실패") }
        val events = DefaultConversationService(MemorySessions(), messages, mapOf(AgentType.CHAT to agent))
            .send(user, ChannelType.CLI, null, UserInput("hi")).toList()
        events.last() shouldBe AgentEvent.Error("엔진 연결 실패")
        messages.items.filter { it.role == ChatRole.ASSISTANT }.shouldBeEmpty()
    }

    test("다른 사용자의 세션 ID로는 이어갈 수 없다") {
        val sessions = MemorySessions()
        val other = sessions.create(UserId(2), ChannelType.CLI, "남의 것")
        val service = DefaultConversationService(sessions, MemoryMessages(), mapOf(AgentType.CHAT to ScriptedAgent {}))
        shouldThrow<SessionNotFoundException> {
            service.send(user, ChannelType.CLI, other.id, UserInput("hi")).toList()
        }
    }
})
