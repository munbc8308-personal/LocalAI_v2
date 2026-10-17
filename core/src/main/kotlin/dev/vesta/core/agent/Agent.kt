package dev.vesta.core.agent

import dev.vesta.core.AgentType
import dev.vesta.core.UserContext
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.session.Session
import kotlinx.coroutines.flow.Flow

/** 에이전트. 라우터가 고른 에이전트가 요청 하나를 처리하고 이벤트를 흘려보낸다 (PROJECT.md 4.8). */
interface Agent {
    val type: AgentType
    fun handle(request: AgentRequest): Flow<AgentEvent>
}

data class AgentRequest(
    val user: UserContext,
    val session: Session,
    /** 이번 사용자 입력까지 포함한 대화 기록 (시간 순). */
    val history: List<ChatMessage>,
)

data class UserInput(val text: String)
