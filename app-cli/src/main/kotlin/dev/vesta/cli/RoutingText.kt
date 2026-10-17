package dev.vesta.cli

import dev.vesta.core.AgentType
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingStage

/** 라우팅 결과를 한 줄로 보여 준다. 예: "웹 리서치 · 결정 모델 0.82 · 24ms" */
internal object RoutingText {
    fun name(agent: AgentType) = when (agent) {
        AgentType.CHAT -> "일반 대화"
        AgentType.DOCS -> "문서 질의"
        AgentType.WEB -> "웹 리서치"
        AgentType.CODE -> "코드 도우미"
        AgentType.TASK -> "작업 에이전트"
        AgentType.PERSONAL -> "개인 비서"
        AgentType.IMAGE -> "이미지 생성"
    }

    fun describe(decision: RoutingDecision): String {
        val stage = when (decision.stage) {
            RoutingStage.EXPLICIT -> "직접 지정"
            RoutingStage.RULE -> "규칙"
            RoutingStage.DECISION_MODEL -> "결정 모델 %.2f".format(decision.confidence)
            RoutingStage.LLM -> "메인 모델 판정"
            RoutingStage.DEFAULT -> "기본값"
        }
        val substituted = decision.requested?.let { " (${name(it)}는 아직 준비 중)" }.orEmpty()
        return "${name(decision.agent)}$substituted · $stage · ${decision.latencyMs}ms"
    }
}
