package dev.vesta.agents

import dev.vesta.core.AgentType
import dev.vesta.core.ModelRole
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.core.knowledge.RetrieveOptions
import dev.vesta.core.knowledge.Retriever
import dev.vesta.core.model.ModelProvider
import dev.vesta.retrieval.ContextAssembler
import dev.vesta.storage.KnowledgeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.ZoneId

/**
 * 등록 문서 기반 질의 (PROJECT.md 4.8 DocsAgent, 5.1).
 * 검색 → 출처 번호를 붙인 컨텍스트 → 인용 규칙과 함께 생성. 근거가 없으면 생성하지 않고 찾지 못했다고 답한다.
 * 질의 재작성(K-09)·근거 검증(K-12)은 M7.
 */
class DocsAgent(
    private val inference: InferenceClient,
    private val models: ModelProvider,
    private val knowledge: KnowledgeRepository,
    private val retriever: Retriever,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val evidenceLimit: Int = 6,
    private val contextBudgetChars: Int = 12_000,
) : Agent {
    override val type = AgentType.DOCS

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val question = request.history.last().content
        val all = knowledge.listCollections(request.user.userId).filter { it.kind == CollectionKind.DOCS }
        val targets = if (request.collections.isEmpty()) all else all.filter { it.name in request.collections }
        val missing = request.collections - targets.map { it.name }.toSet()
        if (targets.isEmpty()) {
            reply(
                if (all.isEmpty()) "등록된 문서 컬렉션이 없습니다. `vesta collection create <이름>`과 `vesta collection add <이름> <경로>`로 문서를 등록하세요."
                else "컬렉션을 찾을 수 없습니다: ${missing.joinToString()} (있는 컬렉션: ${all.joinToString { it.name }})",
            )
            return@flow
        }

        emit(AgentEvent.Status("문서 검색 중 (${targets.joinToString { it.name }})"))
        val found = retriever.retrieve(request.user.userId, listOf(question), RetrieveOptions(targets.map { it.id }, evidenceLimit))
        if (found.isEmpty()) {
            reply("등록된 문서에서 관련 내용을 찾지 못했습니다.")
            return@flow
        }
        val context = ContextAssembler.assemble(found, contextBudgetChars)
        emit(AgentEvent.Sources(context.evidence))

        val spec = models.acquire(ModelRole.GENERAL)
        val system = (request.session.systemPrompt ?: Prompts.base(clock, zone)) + "\n" + Prompts.DOCS_RULES
        val earlier = HistoryWindow.fit(request.history.dropLast(1), ChatAgent.DEFAULT_CONTEXT_CHARS / 2)
            .let { if (it.lastOrNull()?.role == ChatRole.USER) it.dropLast(1) else it }
        val messages = listOf(ChatMessage(ChatRole.SYSTEM, system)) + earlier +
            ChatMessage(ChatRole.USER, "참고 자료:\n${context.text}\n\n질문: $question")
        emit(AgentEvent.Status("생성 중"))
        emitAll(Generation.stream(inference, spec, messages, type))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.reply(text: String) {
        emit(AgentEvent.Token(text))
        emit(AgentEvent.Done(type, "-", null))
    }
}
