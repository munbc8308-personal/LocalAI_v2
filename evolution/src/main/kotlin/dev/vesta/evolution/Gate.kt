package dev.vesta.evolution

import dev.vesta.core.config.EvolutionConfig

/**
 * 메인 모델 하나의 평가 결과 (E-03). 점수는 모두 0~1, 높을수록 좋다.
 * 같은 점검에서 지금 모델과 후보를 같은 평가셋으로 잰다 (날마다 달라지는 Mac 상태·결정 모델의 영향을 같게)
 */
data class Scorecard(
    val model: String,
    /** D1 에이전트 정확도 (결정 모델이 못 정한 질문은 메인 모델이 판정) */
    val routing: Double,
    /** 기준 답 평가셋: 핵심 내용을 담은 비율 (결정 모델이 판정) */
    val answers: Double,
    /** 문서 답변 문장 중 근거로 확인된 비율 (D7). 문서 컬렉션이 없으면 null */
    val grounding: Double?,
    /** 한자·빈 답변·조사 띄움이 없는 답변 비율 */
    val korean: Double,
    /** 도구 호출 JSON을 형식대로 낸 비율 */
    val toolJson: Double,
    val emptyAnswers: Int,
    /** 답변 첫 글자까지 걸린 시간 중앙값 */
    val firstAnswerMs: Long,
    val tokensPerSecond: Double,
    val memoryBytes: Long?,
) {
    /** 종합 점수: 답변 내용 0.4, 라우팅 0.2, 근거 0.15, 한국어 0.15, 도구 형식 0.1 (근거가 없으면 나머지로 다시 나눈다) */
    val composite: Double get() {
        val parts = listOfNotNull(answers to 0.4, routing to 0.2, grounding?.let { it to 0.15 }, korean to 0.15, toolJson to 0.1)
        return parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
    }
}

data class Verdict(val passed: Boolean, val reasons: List<String>, val delta: Double)

/**
 * 후보가 지금 모델을 대신할 만한지 (E-04). 모두 만족해야 통과:
 * - 종합 점수가 [EvolutionConfig.minImprovement] 이상 높다
 * - 어느 항목도 허용 범위([TOLERANCE]) 넘게 떨어지지 않는다 (평가셋이 작아 문항 하나 차이는 흔들림으로 본다)
 * - 빈 답변이 없다, 답변 시작이 [EvolutionConfig.maxSlowdown]배보다 느리지 않다
 */
object Gate {
    /**
     * 항목별로 떨어져도 되는 폭. 같은 모델을 두 번 재면 항목이 3~6%p 흔들린다 (M16 첫 점검: 답변 97→94%, 근거 77→83%):
     * 문항 하나 차이는 흔들림으로 본다
     */
    const val TOLERANCE = 0.05

    fun judge(base: Scorecard, candidate: Scorecard, config: EvolutionConfig): Verdict {
        val reasons = buildList {
            fun drop(name: String, b: Double?, c: Double?) {
                if (b != null && c != null && c < b - TOLERANCE) add("$name 하락 ${pct(b)} → ${pct(c)}")
            }
            drop("답변 내용", base.answers, candidate.answers)
            drop("라우팅", base.routing, candidate.routing)
            drop("근거율", base.grounding, candidate.grounding)
            drop("한국어", base.korean, candidate.korean)
            drop("도구 형식", base.toolJson, candidate.toolJson)
            if (base.grounding != null && candidate.grounding == null) add("근거율을 재지 못함")
            if (candidate.emptyAnswers > 0) add("빈 답변 ${candidate.emptyAnswers}개")
            if (candidate.firstAnswerMs > base.firstAnswerMs * config.maxSlowdown) {
                add("답변 시작 느림 ${base.firstAnswerMs}ms → ${candidate.firstAnswerMs}ms (허용 ${config.maxSlowdown}배)")
            }
            val delta = candidate.composite - base.composite
            if (delta < config.minImprovement) add("종합 점수 차이 ${signed(delta)} (필요 +${pct(config.minImprovement)})")
        }
        return Verdict(reasons.isEmpty(), reasons, candidate.composite - base.composite)
    }

    fun pct(x: Double) = "%.0f%%".format(x * 100)
    fun signed(x: Double) = "%+.1f%%p".format(x * 100)
}
