package dev.vesta.conversation

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.agent.UserInput
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingLogRepository
import dev.vesta.core.decision.RoutingStage
import dev.vesta.router.ExplicitRoute
import dev.vesta.router.Router
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.SessionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

/**
 * 대화 엔진 (PROJECT.md 6.1, 8장).
 * 세션 조회·생성 → 사용자 메시지 저장 → 에이전트 실행 → 답변 저장.
 * 에이전트 선택은 Router (PROJECT.md 4.5: 명시 → 규칙 → 결정 모델 → 메인 모델 → 일반 대화).
 */
class DefaultConversationService(
    private val sessions: SessionRepository,
    private val messages: MessageRepository,
    private val agents: Map<AgentType, Agent>,
    /** null이면 명시 지정·규칙만 쓴다 (테스트·결정 모델 없는 환경) */
    private val router: Router? = null,
    private val routingLog: RoutingLogRepository? = null,
    /** 사용자별로 지금 쓸 수 있는 에이전트 (예: 컬렉션이 있어야 DOCS) */
    private val availability: suspend (UserContext) -> Set<AgentType> = { agents.keys },
) : ConversationService {

    override fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent> = flow {
        val existing = sessionId?.let { sessions.find(user.userId, it) ?: throw SessionNotFoundException(it) }
        val session = existing ?: sessions.create(user.userId, channel, titleOf(input.text))
        emit(AgentEvent.SessionReady(session.id, session.title, created = existing == null))

        messages.append(session.id, NewMessage(ChatRole.USER, input.text))
        val history = messages.list(session.id)
            .filter { it.content.isNotBlank() }
            .map { ChatMessage(it.role, ExplicitRoute.parse(it.content)?.question?.ifBlank { null } ?: it.content) }

        val available = availability(user).filter { it in agents }.toSet()
        val decision = router?.route(input.text, history.dropLast(1), available)
            ?: ExplicitRoute.parse(input.text)?.let { RoutingDecision(it.agent, 1.0, RoutingStage.EXPLICIT, collections = it.collections, question = it.question) }
            ?: RoutingDecision(AgentType.CHAT, 0.0, RoutingStage.DEFAULT, question = input.text)
        emit(AgentEvent.Routed(decision))
        routingLog?.let { log -> runCatching { log.record(session.id, decision) } }
        val agent = agents[decision.agent] ?: agents.getValue(AgentType.CHAT)
        val route = decision
        val answer = StringBuilder()
        var done: AgentEvent.Done? = null
        try {
            agent.handle(AgentRequest(user, session, history.dropLast(1) + ChatMessage(ChatRole.USER, route.question.ifBlank { input.text }), route.collections))
                .catch { error ->
                    log.error(error) { "에이전트 실패: session=${session.id.value}" }
                    emit(AgentEvent.Error(error.message ?: error::class.simpleName.orEmpty()))
                }
                .collect { event ->
                    when (event) {
                        is AgentEvent.Token -> answer.append(event.text)
                        is AgentEvent.Done -> done = event
                        else -> Unit
                    }
                    emit(event)
                }
        } finally {
            // 사용자가 중단(취소)해도 생성된 부분까지는 남긴다 (C-04)
            if (answer.isNotEmpty()) {
                withContext(NonCancellable) {
                    messages.append(
                        session.id,
                        NewMessage(
                            role = ChatRole.ASSISTANT,
                            content = answer.toString(),
                            agent = agent.type,
                            model = done?.model,
                            interrupted = done == null,
                            promptTokens = done?.usage?.promptTokens,
                            completionTokens = done?.usage?.completionTokens,
                        ),
                    )
                }
            }
        }
    }

    /** 첫 질문의 첫 줄로 제목을 만든다. */
    private fun titleOf(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { if (it.length > 40) it.take(40) + "…" else it } ?: "새 대화"
}

class SessionNotFoundException(id: SessionId) : NoSuchElementException("세션을 찾을 수 없습니다: ${id.value}")
