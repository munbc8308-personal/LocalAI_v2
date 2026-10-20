package dev.vesta.core.agent

import dev.vesta.core.AgentType
import dev.vesta.core.SessionId
import dev.vesta.core.inference.Usage

/**
 * 채널이 받아서 보여 주는 이벤트 (PROJECT.md 8.1).
 * 이후 마일스톤에서 Media가 추가된다.
 */
sealed interface AgentEvent {
    /** 세션이 정해졌다. 새 세션이면 [created]가 true. */
    data class SessionReady(val sessionId: SessionId, val title: String?, val created: Boolean) : AgentEvent

    /** 어느 에이전트가 맡았는지 (라우팅 결과, PROJECT.md 4.6). */
    data class Routed(val decision: dev.vesta.core.decision.RoutingDecision) : AgentEvent

    /** 진행 상태 ("생성 중" 등, C-05). */
    data class Status(val text: String) : AgentEvent

    /** 생각 과정 조각. 채널은 보통 "생각 중" 표시만 한다. */
    data class Reasoning(val text: String) : AgentEvent

    /** 답변에 쓴 근거 목록. 번호는 답변의 [n]과 같다 (K-08). */
    data class Sources(val evidence: List<dev.vesta.core.knowledge.Evidence>) : AgentEvent

    /** 도구 실행 시작 (T-08 실행 로그) */
    data class ToolStarted(val callId: String, val tool: String, val risk: dev.vesta.core.RiskLevel, val summary: String) : AgentEvent

    /** 도구 실행 결과 */
    data class ToolFinished(val callId: String, val tool: String, val ok: Boolean, val summary: String) : AgentEvent

    /**
     * 사용자 승인이 필요하다 (T-03). 채널은 승인/거부(셸은 수정 후 승인)를 받아
     * `ConversationService.resolveApproval(requestId, …)`를 부른다.
     */
    data class ApprovalRequested(
        val requestId: String,
        val tool: String,
        val risk: dev.vesta.core.RiskLevel,
        val details: dev.vesta.core.tools.ApprovalDetails,
        val reason: String?,
        /** 수정 후 승인이 가능한 도구(셸)면 수정할 인자 이름과 지금 값. 수정본은 이 인자만 담아 보내면 된다. */
        val editableField: String? = null,
        val editableValue: String? = null,
    ) : AgentEvent

    /** 에이전트가 만든 이미지 등 (V-06). 이미 저장됐고, 채널은 [bytes]를 그대로 보낸다 */
    class Media(val asset: dev.vesta.core.media.MediaAsset, val bytes: ByteArray, val caption: String?) : AgentEvent

    /** 근거 검증 결과 (D7). [unsupported]는 근거로 확인되지 않은 문장. */
    data class Grounding(val checked: Int, val unsupported: List<String>) : AgentEvent

    /** 답변 조각. */
    data class Token(val text: String) : AgentEvent

    /** 답변 완료. */
    data class Done(val agent: AgentType, val model: String, val usage: Usage?) : AgentEvent

    /** 처리 실패. 사용자 메시지는 저장되어 있다. */
    data class Error(val message: String) : AgentEvent
}
