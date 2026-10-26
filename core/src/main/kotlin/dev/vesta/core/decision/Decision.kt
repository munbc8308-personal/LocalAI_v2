package dev.vesta.core.decision

import dev.vesta.core.AgentType

/** 결정 모델 질문 (PROJECT.md 4.4, 8.1). Ollaya `/api/decide`·TypeSafe(Jev) 형식. */
sealed interface DecisionQuestion {
    val instructions: String

    data class Choice(override val instructions: String, val criteria: Map<String, String>) : DecisionQuestion
    data class Score(override val instructions: String, val levels: List<String>) : DecisionQuestion
    data class Noul(override val instructions: String) : DecisionQuestion
}

sealed interface DecisionAnswer {
    data class Choice(val value: String, val confidence: Double, val probabilities: Map<String, Double>) : DecisionAnswer
    data class Score(val level: Double, val confidence: Double) : DecisionAnswer
    data class Noul(val probability: Double) : DecisionAnswer
}

/** 텍스트를 생성하지 않고 판정만 하는 모델 (System 1). */
interface DecisionClient {
    suspend fun decide(state: String, questions: Map<String, DecisionQuestion>): Map<String, DecisionAnswer>
}

/** 라우팅이 어느 단계에서 정해졌는지 (PROJECT.md 4.5). */
enum class RoutingStage { EXPLICIT, RULE, DECISION_MODEL, LLM, DEFAULT }

/** 라우팅 결과 (PROJECT.md 4.6). */
data class RoutingDecision(
    val agent: AgentType,
    val confidence: Double,
    val stage: RoutingStage,
    /** D2: 웹 검색이 필요할 확률 (판정하지 않았으면 null) */
    val needsWeb: Double? = null,
    /** D3: 로컬 문서가 필요할 확률 */
    val needsLocal: Double? = null,
    /** 문서 검색 대상 컬렉션 (명시 지정 시) */
    val collections: List<String> = emptyList(),
    /** 라우팅 명령을 뗀 질문 */
    val question: String,
    val latencyMs: Long = 0,
    /** 판정은 했지만 아직 구현되지 않은 에이전트라 대신 쓴 경우 원래 판정 */
    val requested: AgentType? = null,
    /** 사용자 정의 에이전트로 맡긴 경우 그 이름 (`/agent 이름`, M22). [agent]는 TASK */
    val customAgent: String? = null,
    /** [customAgent]가 팀 이름인 경우 (`/team 이름`, M22) */
    val team: Boolean = false,
    /** 스킬로 부른 경우 그 이름 (`/이름 할 일`, SK-02) */
    val skill: String? = null,
    /** "이어서 해 줘"로 직전 담당을 이어 간 경우 (M32 J-04: 작업 계획을 이어 쓴다) */
    val continued: Boolean = false,
)

/** 라우팅 기록 (PROJECT.md 11장 RoutingLog). 평가·보정(4.7)과 학습 데이터(L-01)에 쓴다. */
interface RoutingLogRepository {
    suspend fun record(sessionId: dev.vesta.core.SessionId, decision: RoutingDecision)
}
