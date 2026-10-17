package dev.vesta.agents

import dev.vesta.core.AgentType
import dev.vesta.core.ModelRole
import dev.vesta.core.SourceType
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.knowledge.Collection
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.knowledge.RetrieveOptions
import dev.vesta.core.knowledge.Retriever
import dev.vesta.core.model.ModelProvider
import dev.vesta.retrieval.ContextAssembler
import dev.vesta.storage.KnowledgeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/**
 * 코드 도우미 (PROJECT.md 4.8 CodeAgent, 7.6).
 * - 저장소 질문(@저장소, 문서 필요 판정, "우리 코드" 등) → 등록한 코드 저장소 검색 후 파일:줄과 함께 답 (D-03)
 * - 질문에 저장소 안 파일 경로가 있으면 그 파일(범위)을 그대로 붙인다 (D-04)
 * - 그 밖의 일반 코딩 질문 → 검색 없이 code 역할 모델로 답
 * 수정안은 unified diff로 제시한다 (D-06 적용은 M6 승인 도구).
 */
class CodeAgent(
    private val inference: InferenceClient,
    private val models: ModelProvider,
    private val knowledge: KnowledgeRepository,
    private val retriever: Retriever,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val evidenceLimit: Int = 8,
    private val contextBudgetChars: Int = 16_000,
    private val maxAttachedLines: Int = 400,
) : Agent {
    override val type = AgentType.CODE

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val question = request.history.last().content
        val repos = knowledge.listCollections(request.user.userId).filter { it.kind == CollectionKind.CODE }
        val targets = if (request.collections.isEmpty()) repos else repos.filter { it.name in request.collections }

        val attached = attachments(question, targets)
        val wantsRepo = targets.isNotEmpty() && (
            request.collections.isNotEmpty() || attached.isNotEmpty() ||
                (request.routing?.needsLocal ?: 0.0) >= 0.5 || REPO_HINT.containsMatchIn(question)
            )

        val evidence = mutableListOf<Evidence>()
        evidence += attached
        if (wantsRepo) {
            emit(AgentEvent.Status("코드 검색 중 (${targets.joinToString { it.name }})"))
            evidence += retriever.retrieve(request.user.userId, listOf(question), RetrieveOptions(targets.map { it.id }, evidenceLimit))
                .filterNot { found -> attached.any { it.ref == found.ref } }
        }

        val spec = models.acquire(ModelRole.CODE)
        val base = (request.session.systemPrompt ?: Prompts.base(clock, zone)) + "\n" + Prompts.CODE_RULES
        val earlier = HistoryWindow.fit(request.history.dropLast(1), ChatAgent.DEFAULT_CONTEXT_CHARS / 2)
            .let { if (it.lastOrNull()?.role == ChatRole.USER) it.dropLast(1) else it }
        val messages = if (evidence.isEmpty()) {
            if (wantsRepo) emit(AgentEvent.Status("관련 코드를 찾지 못해 일반 지식으로 답합니다"))
            listOf(ChatMessage(ChatRole.SYSTEM, base)) + earlier + ChatMessage(ChatRole.USER, question)
        } else {
            val context = ContextAssembler.assemble(evidence, contextBudgetChars)
            emit(AgentEvent.Sources(context.evidence))
            listOf(ChatMessage(ChatRole.SYSTEM, base + "\n" + Prompts.CODE_CONTEXT_RULES)) + earlier +
                ChatMessage(ChatRole.USER, "코드 자료:\n${context.text}\n\n질문: $question")
        }
        emit(AgentEvent.Status("생성 중"))
        emitAll(Generation.stream(inference, spec, messages, type))
    }

    /** 질문에 적힌 `경로[:시작[-끝]]` 중 등록 저장소 안에 실제로 있는 파일을 붙인다. */
    private fun attachments(question: String, repos: List<Collection>): List<Evidence> =
        FILE_REF.findAll(question).mapNotNull { match ->
            val (path, from, to) = Triple(match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[3].toIntOrNull())
            val (repo, file) = repos.firstNotNullOfOrNull { repo ->
                repo.rootPath?.let { root -> Path.of(root).resolve(path).normalize().takeIf { it.startsWith(root) && it.isRegularFile() } }
                    ?.let { repo to it }
            } ?: return@mapNotNull null
            val lines = file.readLines()
            val start = (from ?: 1).coerceIn(1, maxOf(1, lines.size))
            val end = (to ?: from?.let { it + 60 } ?: lines.size).coerceIn(start, lines.size).coerceAtMost(start + maxAttachedLines - 1)
            val numbered = (start..end).joinToString("\n") { "%4d  %s".format(it, lines[it - 1]) }
            Evidence(SourceType.CODE, file.toString(), path, "${repo.name} · $path:$start–$end", numbered, 1.0)
        }.distinctBy { it.ref + it.location }.toList()

    private companion object {
        val REPO_HINT = Regex("저장소|레포|리포|repo|코드베이스|우리 (코드|프로젝트)|내 프로젝트|이 프로젝트|어디서 (쓰|사용|처리|호출)|어디에 (있|정의)|구현(돼|되어) ?있")
        val FILE_REF = Regex("([\\w./-]+\\.(?:kt|kts|java|py|js|ts|tsx|go|rs|swift|rb|sql|sh|yaml|yml|toml|gradle|md))(?::(\\d+)(?:[-–](\\d+))?)?")
    }
}
