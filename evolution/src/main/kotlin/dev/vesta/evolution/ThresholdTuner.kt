package dev.vesta.evolution

import dev.vesta.core.AgentType

/** 라우팅 평가 한 문항: 결정 모델 판정·신뢰도와 메인 모델 판정을 한 번씩만 구해 두고 임계값을 바꿔 가며 계산한다 */
data class RouteSample(val question: String, val expected: AgentType, val decision: AgentType?, val confidence: Double, val llm: AgentType?)

/** 임계값 조정 결과. [value]가 지금 값과 같으면 바꾸지 않는다 */
data class TuneResult(
    val key: String,
    val current: Double,
    val value: Double,
    /** 맞추는 데 쓴 문항 / 확인용(holdout) 문항 정확도: 지금 값 → 새 값 */
    val trainBefore: Double,
    val trainAfter: Double,
    val holdoutBefore: Double,
    val holdoutAfter: Double,
    val note: String,
) {
    val changed: Boolean get() = value != current
}

/**
 * 임계값 조정 (E-06). 평가셋을 질문 해시로 나눠(3분의 1은 확인용) 맞추는 쪽에서 고르고 확인용에서 떨어지지 않을 때만 바꾼다:
 * 같은 평가셋에 매주 맞추면 평가셋에만 좋은 값이 되는 것(과적합)을 줄인다
 */
object ThresholdTuner {
    fun holdout(question: String) = Math.floorMod(question.trim().hashCode(), 3) == 0

    /**
     * 라우터 결정 모델 신뢰도 임계값 (`decision.thresholds.agent`). 정확도가 오를 때만 바꾼다.
     * 정확도가 같은 값들 중에서는 지금 값에 가장 가까운 것: 평가셋에서 같아도 처음 보는 질문에서는 낮은 임계값이 더 틀릴 수 있다
     */
    fun agent(samples: List<RouteSample>, current: Double, grid: List<Double> = (30..90 step 5).map { it / 100.0 }): TuneResult {
        val (check, fit) = samples.partition { holdout(it.question) }
        fun routed(s: RouteSample, t: Double) = if (s.decision != null && s.confidence >= t) s.decision else s.llm ?: AgentType.CHAT
        fun accuracy(set: List<RouteSample>, t: Double) = if (set.isEmpty()) 0.0 else set.count { routed(it, t) == it.expected }.toDouble() / set.size
        fun llmShare(t: Double) = samples.count { !(it.decision != null && it.confidence >= t) }.toDouble() / maxOf(1, samples.size)

        val best = grid.maxWith(compareBy<Double> { accuracy(fit, it) }.thenBy { -kotlin.math.abs(it - current) })
        val better = accuracy(fit, best) > accuracy(fit, current)
        val safe = accuracy(check, best) >= accuracy(check, current)
        val value = if (better && safe) best else current
        return TuneResult(
            "decision.thresholds.agent", current, value,
            accuracy(fit, current), accuracy(fit, value), accuracy(check, current), accuracy(check, value),
            "메인 모델 판정 ${Gate.pct(llmShare(current))} → ${Gate.pct(llmShare(value))}" +
                if (!safe && better) " · 확인용 문항에서 떨어져 바꾸지 않음" else "",
        )
    }

    /**
     * D6 코드 근거 충분도 임계값 (`decision.thresholds.codeSufficient`). [answerable]은 저장소에 답이 있는 질문의 D6 점수,
     * [absent]는 없는 질문의 점수. 맞추는 쪽에서 둘을 가장 잘 가르는 구간의 가운데를 고른다
     */
    fun codeSufficient(answerable: List<Pair<String, Double>>, absent: List<Pair<String, Double>>, current: Double): TuneResult {
        val all = answerable.map { Triple(it.first, it.second, true) } + absent.map { Triple(it.first, it.second, false) }
        val (check, fit) = all.partition { holdout(it.first) }
        fun accuracy(set: List<Triple<String, Double, Boolean>>, t: Double) =
            if (set.isEmpty()) 0.0 else set.count { (it.second >= t) == it.third }.toDouble() / set.size
        // 후보: 맞추는 쪽 점수 사이의 가운데들
        val points = fit.map { it.second }.sorted()
        val grid = points.zipWithNext { a, b -> ((a + b) / 2 * 1000).toLong() / 1000.0 }.filter { it in 0.05..0.9 }.ifEmpty { listOf(current) }
        val bestAccuracy = grid.maxOf { accuracy(fit, it) }
        // 가장 정확한 값들 중 지금 값에 가장 가까운 것 (불필요하게 흔들지 않게)
        val best = grid.filter { accuracy(fit, it) == bestAccuracy }.minBy { kotlin.math.abs(it - current) }
        val better = bestAccuracy > accuracy(fit, current)
        val safe = accuracy(check, best) >= accuracy(check, current)
        val value = if (better && safe) best else current
        val minAnswerable = answerable.minOfOrNull { it.second }
        val maxAbsent = absent.maxOfOrNull { it.second }
        return TuneResult(
            "decision.thresholds.codeSufficient", current, value,
            accuracy(fit, current), accuracy(fit, value), accuracy(check, current), accuracy(check, value),
            "있는 질문 최저 ${minAnswerable?.let { "%.3f".format(it) } ?: "-"}, 없는 질문 최고 ${maxAbsent?.let { "%.3f".format(it) } ?: "-"}" +
                if (!safe && better) " · 확인용 문항에서 떨어져 바꾸지 않음" else "",
        )
    }
}
