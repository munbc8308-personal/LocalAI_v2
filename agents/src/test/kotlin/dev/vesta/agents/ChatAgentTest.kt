package dev.vesta.agents

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.ModelRole
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.inference.ModelInfo
import dev.vesta.core.inference.Usage
import dev.vesta.core.model.ModelProvider
import dev.vesta.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import java.time.Instant

class ChatAgentTest : FunSpec({
    val session = Session(SessionId("s"), UserId(1), null, ChannelType.CLI, null, Instant.EPOCH, Instant.EPOCH)
    val user = UserContext(UserId(1), UserRole.OWNER)
    val spec = ModelSpec("main", keepAlive = "forever", reasoningEffort = "low")

    class FakeInference(private vararg val chunks: ChatChunk) : InferenceClient {
        var lastRequest: ChatRequest? = null
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun embed(model: String, texts: List<String>) = emptyList<FloatArray>()
        override fun chat(request: ChatRequest): Flow<ChatChunk> {
            lastRequest = request
            return flowOf(*chunks)
        }
    }

    val models = object : ModelProvider {
        override suspend fun acquire(role: ModelRole) = spec
    }

    test("모델 설정(생각 양·keep_alive)을 요청에 싣고 조각을 이벤트로 바꾼다") {
        val inference = FakeInference(ChatChunk.Reasoning("음"), ChatChunk.Delta("안녕"), ChatChunk.Done("stop", Usage(1, 2)))
        val events = ChatAgent(inference, models).handle(
            AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "hi"))),
        ).toList()

        events shouldContainExactly listOf(
            AgentEvent.Status("생성 중"),
            AgentEvent.Reasoning("음"),
            AgentEvent.Token("안녕"),
            AgentEvent.Done(AgentType.CHAT, "main", Usage(1, 2)),
        )
        val request = inference.lastRequest!!
        request.reasoningEffort shouldBe "low"
        request.keepAlive shouldBe "forever"
        request.messages.first().role shouldBe ChatRole.SYSTEM
        request.messages.first().content shouldContain "Vesta"
        request.messages.last().content shouldBe "hi"
    }

    test("기록이 길면 최근 것만 남기고 user로 시작한다") {
        val history = listOf(
            ChatMessage(ChatRole.USER, "a".repeat(50)),
            ChatMessage(ChatRole.ASSISTANT, "b".repeat(50)),
            ChatMessage(ChatRole.USER, "c".repeat(10)),
            ChatMessage(ChatRole.ASSISTANT, "d".repeat(10)),
            ChatMessage(ChatRole.USER, "마지막"),
        )
        val fitted = HistoryWindow.fit(history, maxChars = 30)
        fitted.map { it.content.first() } shouldBe listOf('c', 'd', '마')
    }

    test("마지막 입력은 상한보다 길어도 넣는다") {
        HistoryWindow.fit(listOf(ChatMessage(ChatRole.USER, "x".repeat(100))), maxChars = 10).size shouldBe 1
    }
})
