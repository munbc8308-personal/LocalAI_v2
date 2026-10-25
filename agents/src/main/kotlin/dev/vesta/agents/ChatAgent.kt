package dev.vesta.agents

import dev.vesta.core.AgentType
import dev.vesta.core.ModelRole
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.model.ModelNotConfiguredException
import dev.vesta.core.model.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.ZoneId

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
        val history = HistoryWindow.fit(request.history, contextChars)
        // 이미지가 있으면 비전 모델 (V-04). 설정에 없으면 메인 모델 (Gemma 4는 메인 모델이 비전 지원)
        val spec = if (history.any { it.images.isNotEmpty() }) {
            try {
                models.acquire(ModelRole.VISION)
            } catch (_: ModelNotConfiguredException) {
                models.acquire(ModelRole.GENERAL)
            }
        } else models.acquire(ModelRole.GENERAL)
        val system = Prompts.system(request, clock, zone)
        val messages = listOf(ChatMessage(ChatRole.SYSTEM, system)) + history
        emit(AgentEvent.Status("생성 중"))
        emitAll(Generation.stream(inference, spec, messages, type))
    }

    companion object {
        /** 대화 기록에 쓸 글자 수 상한 (C-07 초기 구현). 토큰 기준 관리는 M7에서. */
        const val DEFAULT_CONTEXT_CHARS = 24_000
    }
}

/** 최근 대화부터 거꾸로 담아 글자 수 상한 안에 맞춘다. 마지막 사용자 입력은 항상 넣는다. */
internal object HistoryWindow {
    /** 같은 줄(빈 줄 제외)이 세 번 넘게 나오면 두 번째 뒤로는 지우고 몇 번 되풀이했는지 남긴다 */
    fun collapse(text: String): String {
        val lines = text.lines()
        val counts = lines.filter { it.isNotBlank() }.groupingBy { it.trim() }.eachCount()
        if (counts.values.none { it > 3 }) return text
        val seen = mutableMapOf<String, Int>()
        val out = lines.filter { line ->
            val key = line.trim()
            if (key.isEmpty()) return@filter true
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            n <= 2
        }
        val note = counts.filter { it.value > 3 }.entries.joinToString("; ") { "\"${it.key.take(40)}\" ${it.value}번" }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n") + "\n(같은 줄 되풀이 생략: $note)"
    }

    /** 메시지 하나가 기록에서 차지할 수 있는 최대 글자 */
    const val PER_MESSAGE = 8_000

    fun fit(history: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        val result = ArrayDeque<ChatMessage>()
        var used = 0
        for ((index, raw) in history.asReversed().withIndex()) {
            // 지난 답에 같은 줄이 되풀이돼 있으면 접는다 (되풀이 글이 다음 요청에서 또 되풀이를 부르지 않게)
            val original = if (index > 0) raw.copy(content = collapse(raw.content)) else raw
            // 아주 긴 지난 메시지 하나(끝없이 쓴 답 등)가 앞 맥락을 모두 밀어내지 않게 앞뒤만 남긴다. 지금 질문(첨부 파일 등)은 그대로
            val message = if (index > 0 && original.content.length > PER_MESSAGE) original.copy(content = original.content.take(PER_MESSAGE / 2) +
                "\n…(${original.content.length - PER_MESSAGE}자 생략)…\n" + original.content.takeLast(PER_MESSAGE / 2)) else original
            if (result.isNotEmpty() && used + message.content.length > maxChars) break
            result.addFirst(message)
            used += message.content.length
        }
        // 잘린 기록이 assistant로 시작하면 어색하므로 user부터 시작하게 맞춘다
        while (result.size > 1 && result.first().role != ChatRole.USER) result.removeFirst()
        return result.toList()
    }
}
