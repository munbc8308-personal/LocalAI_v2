package dev.vesta.agents

import dev.vesta.core.AgentType
import dev.vesta.core.ModelRole
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.model.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 일반 대화 에이전트 (PROJECT.md 4.8). 정보원·도구 없이 메인 모델로 답한다. */
class ChatAgent(
    private val inference: InferenceClient,
    private val models: ModelProvider,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val contextChars: Int = DEFAULT_CONTEXT_CHARS,
) : Agent {
    override val type = AgentType.CHAT

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val spec = models.acquire(ModelRole.GENERAL)
        val system = request.session.systemPrompt ?: defaultSystemPrompt()
        val messages = listOf(ChatMessage(ChatRole.SYSTEM, system)) + HistoryWindow.fit(request.history, contextChars)
        val chatRequest = ChatRequest(
            model = spec.name,
            messages = messages,
            temperature = spec.temperature,
            maxTokens = spec.maxTokens,
            reasoningEffort = spec.reasoningEffort,
            keepAlive = spec.keepAlive,
        )
        emit(AgentEvent.Status("생성 중"))
        inference.chat(chatRequest).collect { chunk ->
            when (chunk) {
                is ChatChunk.Reasoning -> emit(AgentEvent.Reasoning(chunk.text))
                is ChatChunk.Delta -> emit(AgentEvent.Token(chunk.text))
                is ChatChunk.Done -> emit(AgentEvent.Done(type, spec.name, chunk.usage))
            }
        }
    }

    private fun defaultSystemPrompt(): String {
        val now = ZonedDateTime.now(clock.withZone(zone))
        val date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd (E) HH:mm", Locale.KOREAN))
        return """
            너는 Vesta다. 사용자의 Mac에서만 동작하는 로컬 AI 비서다.
            - 사용자가 쓴 언어로 답한다. 한국어 질문에는 자연스러운 한국어로 답한다.
            - 대화는 저장되어 다음에도 이어진다. 아래 대화 기록에 있는 내용(사용자가 알려 준 이름·선호 등)은 기억하고 활용한다.
            - 모르거나 확실하지 않은 내용은 추측하지 말고 모른다고 말한다.
            - 답은 간결하게 쓰고, 필요할 때만 목록·표·코드 블록을 쓴다.
            현재 시각: $date ($zone)
        """.trimIndent()
    }

    companion object {
        /** 대화 기록에 쓸 글자 수 상한 (C-07 초기 구현). 토큰 기준 관리는 M7에서. */
        const val DEFAULT_CONTEXT_CHARS = 24_000
    }
}

/** 최근 대화부터 거꾸로 담아 글자 수 상한 안에 맞춘다. 마지막 사용자 입력은 항상 넣는다. */
internal object HistoryWindow {
    fun fit(history: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        val result = ArrayDeque<ChatMessage>()
        var used = 0
        for (message in history.asReversed()) {
            if (result.isNotEmpty() && used + message.content.length > maxChars) break
            result.addFirst(message)
            used += message.content.length
        }
        // 잘린 기록이 assistant로 시작하면 어색하므로 user부터 시작하게 맞춘다
        while (result.size > 1 && result.first().role != ChatRole.USER) result.removeFirst()
        return result.toList()
    }
}
