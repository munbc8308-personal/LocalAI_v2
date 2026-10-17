package dev.vesta.agents

import dev.vesta.core.AgentType
import dev.vesta.core.ModelRole
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.config.WebMode
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.model.ModelProvider
import dev.vesta.retrieval.ContextAssembler
import dev.vesta.retrieval.WebProgress
import dev.vesta.retrieval.WebRetriever
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.ZoneId

/**
 * 웹 리서치 (PROJECT.md 4.8 WebResearchAgent, 5.1·5.3·5.4).
 * 검색어 생성 → 보낸 검색어 표시 → 검색·수집 → 출처 번호 컨텍스트 → 인용 규칙과 함께 생성.
 * 질문에 URL이 있으면 검색하지 않고 그 페이지를 읽는다 (W-09).
 */
class WebResearchAgent(
    private val inference: InferenceClient,
    private val models: ModelProvider,
    private val rewriter: QueryRewriter,
    private val retriever: WebRetriever,
    private val mode: () -> WebMode,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val contextBudgetChars: Int = 12_000,
) : Agent {
    override val type = AgentType.WEB

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        if (mode() == WebMode.OFF) {
            reply("웹 검색이 꺼져 있습니다 (설정 web.mode: OFF). 켜려면 ~/.vesta/config.yaml에서 AUTO 또는 ALWAYS로 바꾸세요.")
            return@flow
        }
        val question = request.history.last().content
        val urls = URL.findAll(question).map { it.value.trimEnd('.', ',', ')', '>', '"') }.distinct().toList()
        val queries = if (urls.isEmpty()) rewriter.webQueries(request.history) else emptyList()
        if (urls.isNotEmpty()) emit(AgentEvent.Status("URL 읽기: ${urls.joinToString()}"))

        val result = retriever.retrieve(question, queries, urls) { progress ->
            when (progress) {
                is WebProgress.Searching -> emit(AgentEvent.Status("웹 검색: \"${progress.query}\""))
                is WebProgress.Reading -> emit(AgentEvent.Status("페이지 ${progress.count}개 읽는 중"))
            }
        }
        if (result.evidence.isEmpty()) {
            val why = result.failures.firstOrNull()?.let { " (${it.reason})" }.orEmpty()
            reply(if (urls.isNotEmpty()) "페이지를 읽지 못했습니다$why." else "웹에서 관련 내용을 찾지 못했습니다$why.")
            return@flow
        }
        val context = ContextAssembler.assemble(result.evidence, contextBudgetChars)
        emit(AgentEvent.Sources(context.evidence))

        val spec = models.acquire(ModelRole.GENERAL)
        val system = (request.session.systemPrompt ?: Prompts.base(clock, zone)) + "\n" + Prompts.WEB_RULES
        val earlier = HistoryWindow.fit(request.history.dropLast(1), ChatAgent.DEFAULT_CONTEXT_CHARS / 2)
            .let { if (it.lastOrNull()?.role == ChatRole.USER) it.dropLast(1) else it }
        val messages = listOf(ChatMessage(ChatRole.SYSTEM, system)) + earlier +
            ChatMessage(ChatRole.USER, "웹 자료:\n${context.text}\n\n질문: $question")
        emit(AgentEvent.Status("생성 중"))
        emitAll(Generation.stream(inference, spec, messages, type))
    }

    private suspend fun FlowCollector<AgentEvent>.reply(text: String) {
        emit(AgentEvent.Token(text))
        emit(AgentEvent.Done(type, "-", null))
    }

    private companion object {
        val URL = Regex("https?://[^\\s<>\"']+")
    }
}
