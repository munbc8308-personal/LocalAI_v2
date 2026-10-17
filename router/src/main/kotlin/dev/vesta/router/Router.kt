package dev.vesta.router

import dev.vesta.core.AgentType
import dev.vesta.core.config.DecisionConfig
import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingStage
import dev.vesta.core.inference.ChatMessage
import dev.vesta.decision.RoutingQuestions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * 에이전트 선택 (PROJECT.md 4.5).
 * 1 명시 지정 → 2 규칙 → 3 결정 모델(신뢰도 ≥ 임계값일 때만) → 4 메인 모델 JSON 판정 → 5 일반 대화.
 * 판정된 에이전트가 아직 없으면 [available]에서 대신 쓸 에이전트를 고르고 원래 판정은 `requested`에 남긴다.
 */
class Router(
    private val config: DecisionConfig,
    private val decision: DecisionClient?,
    private val llm: LlmRouter?,
) {
    /**
     * @param available 지금 쓸 수 있는 에이전트 (구현됨 + 조건 충족, 예: 컬렉션이 있어야 DOCS)
     * @param recent 직전 대화 (이번 질문 제외)
     */
    suspend fun route(text: String, recent: List<ChatMessage>, available: Set<AgentType>): RoutingDecision {
        val start = TimeSource.Monotonic.markNow()
        fun done(decision: RoutingDecision) = substitute(decision.copy(latencyMs = start.elapsedNow().inWholeMilliseconds), available)

        // 1~2단계: 명시 지정, URL 규칙
        ExplicitRoute.parse(text)?.let { route ->
            val stage = if (text.trim().startsWith("/") || text.trim().startsWith("@")) RoutingStage.EXPLICIT else RoutingStage.RULE
            return done(RoutingDecision(route.agent, 1.0, stage, collections = route.collections, question = route.question))
        }
        if (CODE_BLOCK.containsMatchIn(text)) return done(RoutingDecision(AgentType.CODE, 1.0, RoutingStage.RULE, question = text))

        // 3단계: 결정 모델. 이어지는 짧은 질문("그거 더 자세히")은 문맥이 필요하므로 메인 모델로 넘긴다
        val model = decision
        if (config.enabled && model != null && !(recent.isNotEmpty() && text.length < FOLLOW_UP_CHARS)) {
            runCatching { model.decide(text, RoutingQuestions.questions(config.criteriaLanguage)) }
                .onFailure { log.warn(it) { "결정 모델 실패, 다음 단계로" } }
                .getOrNull()
                ?.let { answers ->
                    val agent = answers[RoutingQuestions.AGENT] as? DecisionAnswer.Choice
                    val web = (answers[RoutingQuestions.NEEDS_WEB] as? DecisionAnswer.Noul)?.probability
                    val local = (answers[RoutingQuestions.NEEDS_LOCAL] as? DecisionAnswer.Noul)?.probability
                    val type = agent?.value?.let { runCatching { AgentType.valueOf(it) }.getOrNull() }
                    if (type != null && agent.confidence >= config.thresholds.agent) {
                        return done(RoutingDecision(type, agent.confidence, RoutingStage.DECISION_MODEL, web, local, question = text))
                    }
                }
        }

        // 4단계: 메인 모델 판정
        llm?.let { router ->
            runCatching { router.judge(text, recent) }
                .onFailure { log.warn(it) { "메인 모델 라우팅 실패" } }
                .getOrNull()
                ?.let { verdict ->
                    return done(
                        RoutingDecision(
                            agent = verdict.agent,
                            confidence = verdict.confidence,
                            stage = RoutingStage.LLM,
                            needsWeb = if (verdict.needsWeb) 1.0 else 0.0,
                            needsLocal = if (verdict.needsLocal) 1.0 else 0.0,
                            question = text,
                        ),
                    )
                }
        }

        // 5단계: 일반 대화
        return done(RoutingDecision(AgentType.CHAT, 0.0, RoutingStage.DEFAULT, question = text))
    }

    /** 아직 없는 에이전트 대신 쓸 에이전트. 코드 질문은 같은 메인 모델이 일반 대화로 답할 수 있다. */
    private fun substitute(decision: RoutingDecision, available: Set<AgentType>): RoutingDecision {
        if (decision.agent in available) return decision
        val fallback = when {
            decision.agent == AgentType.DOCS && AgentType.DOCS !in available -> AgentType.CHAT
            (decision.needsWeb ?: 0.0) >= config.thresholds.needsWeb && AgentType.WEB in available -> AgentType.WEB
            else -> AgentType.CHAT
        }
        return decision.copy(agent = fallback, requested = decision.agent)
    }

    private companion object {
        val CODE_BLOCK = Regex("```")
        /** 이보다 짧은 후속 질문은 결정 모델 대신 문맥을 보는 메인 모델이 판정 */
        const val FOLLOW_UP_CHARS = 20
    }
}
