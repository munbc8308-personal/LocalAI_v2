package dev.vesta.core.agent

import dev.vesta.core.AgentType
import dev.vesta.core.SessionId
import dev.vesta.core.inference.Usage

/**
 * 채널이 받아서 보여 주는 이벤트 (PROJECT.md 8.1).
 * 이후 마일스톤에서 ToolCall, ApprovalRequest, Media가 추가된다.
 */
sealed interface AgentEvent {
    /** 세션이 정해졌다. 새 세션이면 [created]가 true. */
    data class SessionReady(val sessionId: SessionId, val title: String?, val created: Boolean) : AgentEvent

    /** 진행 상태 ("생성 중" 등, C-05). */
    data class Status(val text: String) : AgentEvent

    /** 생각 과정 조각. 채널은 보통 "생각 중" 표시만 한다. */
    data class Reasoning(val text: String) : AgentEvent

    /** 답변에 쓴 근거 목록. 번호는 답변의 [n]과 같다 (K-08). */
    data class Sources(val evidence: List<dev.vesta.core.knowledge.Evidence>) : AgentEvent

    /** 답변 조각. */
    data class Token(val text: String) : AgentEvent

    /** 답변 완료. */
    data class Done(val agent: AgentType, val model: String, val usage: Usage?) : AgentEvent

    /** 처리 실패. 사용자 메시지는 저장되어 있다. */
    data class Error(val message: String) : AgentEvent
}
