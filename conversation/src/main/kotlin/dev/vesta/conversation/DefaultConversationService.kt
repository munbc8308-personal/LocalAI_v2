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
 * 에이전트 선택: 명시 지정(/docs, @컬렉션)이 있으면 그것, 없으면 ChatAgent. M4에서 Router가 고른다.
 */
class DefaultConversationService(
    private val sessions: SessionRepository,
    private val messages: MessageRepository,
    private val agents: Map<AgentType, Agent>,
) : ConversationService {

    override fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent> = flow {
        val existing = sessionId?.let { sessions.find(user.userId, it) ?: throw SessionNotFoundException(it) }
        val session = existing ?: sessions.create(user.userId, channel, titleOf(input.text))
        emit(AgentEvent.SessionReady(session.id, session.title, created = existing == null))

        messages.append(session.id, NewMessage(ChatRole.USER, input.text))
        val route = ExplicitRoute.parse(input.text)
        val history = messages.list(session.id)
            .filter { it.content.isNotBlank() }
            .map { ChatMessage(it.role, ExplicitRoute.parse(it.content)?.question?.ifBlank { null } ?: it.content) }

        val agent = route?.agent?.let { agents[it] } ?: agents.getValue(AgentType.CHAT)
        val answer = StringBuilder()
        var done: AgentEvent.Done? = null
        try {
            agent.handle(AgentRequest(user, session, history, route?.collections.orEmpty()))
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
