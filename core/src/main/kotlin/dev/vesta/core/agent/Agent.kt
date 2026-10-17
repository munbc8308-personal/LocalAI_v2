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
    /** 이번 사용자 입력까지 포함한 대화 기록 (시간 순). 마지막은 라우팅 명령을 뗀 질문. */
    val history: List<ChatMessage>,
    /** 문서 검색 대상 컬렉션 이름. 비어 있으면 사용자의 모든 컬렉션. */
    val collections: List<String> = emptyList(),
)

data class UserInput(val text: String)
