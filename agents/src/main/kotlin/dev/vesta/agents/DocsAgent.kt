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
import dev.vesta.decision.Judge
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
    /** K-09 질의 재작성 (없으면 질문 그대로) */
    private val rewriter: QueryRewriter? = null,
    /** D4·D6·D7 판정 (없으면 건너뜀) */
    private val judge: Judge? = null,
) : Agent {
    override val type = AgentType.DOCS

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val question = request.history.last().content
        val all = knowledge.accessibleCollections(request.user.userId).filter { it.kind == CollectionKind.DOCS }
        var targets = if (request.collections.isEmpty()) all else all.filter { it.name in request.collections }
        val missing = request.collections - targets.map { it.name }.toSet()
        if (targets.isEmpty()) {
            reply(
                if (all.isEmpty()) "등록된 문서 컬렉션이 없습니다. 웹 화면의 '컬렉션'에서 만들고 문서(.txt·.md·.pdf)를 올리거나, 이 Mac 터미널에서 `vesta collection create <이름>`·`vesta collection add <이름> <경로>`로 등록하세요."
                else "컬렉션을 찾을 수 없습니다: ${missing.joinToString()} (있는 컬렉션: ${all.joinToString { it.name }})",
            )
            return@flow
        }

        // D4: 지정이 없고 컬렉션이 여럿이면 설명을 보고 관련 있는 것만
        if (request.collections.isEmpty() && targets.size > 1 && judge != null) {
            val picked = judge.collections(question, targets.associate { it.name to (it.description ?: it.name) })
            if (picked.isNotEmpty()) targets = targets.filter { it.name in picked }
        }
        emit(AgentEvent.Status("문서 검색 중 (${targets.joinToString { it.name }})"))
        // K-09: 이어지는 질문이면 대화 문맥을 반영한 질의로
        val first = if (request.history.size > 1 && rewriter != null) {
            (listOf(question) + rewriter.localQueries(request.history)).distinct()
        } else listOf(question)
        val ids = targets.map { it.id }
        val found = searchUntilSufficient(
            question, first, judge,
            search = { queries -> retriever.retrieve(request.user.userId, queries, RetrieveOptions(ids, evidenceLimit)) },
            nextQueries = { tried -> rewriter?.localQueries(request.history, avoid = tried).orEmpty() },
            limit = evidenceLimit,
        )
        if (found.isEmpty()) {
            reply("등록된 문서에서 관련 내용을 찾지 못했습니다.")
            return@flow
        }
        val context = ContextAssembler.assemble(found, contextBudgetChars)
        emit(AgentEvent.Sources(context.evidence))

        val spec = models.acquire(ModelRole.GENERAL)
        val system = Prompts.system(request, clock, zone) + "\n" + Prompts.DOCS_RULES
        val earlier = HistoryWindow.fit(request.history.dropLast(1), ChatAgent.DEFAULT_CONTEXT_CHARS / 2)
            .let { if (it.lastOrNull()?.role == ChatRole.USER) it.dropLast(1) else it }
        val messages = listOf(ChatMessage(ChatRole.SYSTEM, system)) + earlier +
            ChatMessage(ChatRole.USER, "참고 자료:\n${context.text}\n\n질문: $question")
        emit(AgentEvent.Status("생성 중"))
        emitAll(Generation.stream(inference, spec, messages, type).verifiedAgainst(judge, context.text))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.reply(text: String) {
        emit(AgentEvent.Token(text))
        emit(AgentEvent.Done(type, "-", null))
    }
}
