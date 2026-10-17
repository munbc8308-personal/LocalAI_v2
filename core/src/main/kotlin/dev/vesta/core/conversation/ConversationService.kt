package dev.vesta.core.conversation

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import kotlinx.coroutines.flow.Flow

/**
 * 모든 채널이 공유하는 대화 엔진 (PROJECT.md 6.1).
 * 응답 중단은 반환된 Flow 수집을 취소하면 된다 (C-04). 중단돼도 생성된 부분까지 저장한다.
 */
interface ConversationService {
    /** [sessionId]가 null이면 새 세션을 만든다. */
    fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent>
}
