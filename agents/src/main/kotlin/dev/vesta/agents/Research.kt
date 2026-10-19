package dev.vesta.agents

import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.knowledge.Evidence
import dev.vesta.decision.Judge
import kotlinx.coroutines.flow.FlowCollector

/**
 * 근거가 충분할 때까지 검색 (PROJECT.md 5.1 [3] D6, W-10).
 * 찾은 근거로 답할 수 있는지 판정하고, 부족하면 검색어를 바꿔 다시 찾는다 (최대 [maxAttempts]번). 새로 찾은 근거는 뒤에 붙인다.
 */
internal suspend fun FlowCollector<AgentEvent>.searchUntilSufficient(
    question: String,
    firstQueries: List<String>,
    judge: Judge?,
    search: suspend (List<String>) -> List<Evidence>,
    nextQueries: suspend (tried: List<String>) -> List<String>,
    limit: Int,
    maxAttempts: Int = 3,
): List<Evidence> {
    val tried = mutableListOf<String>()
    var found = emptyList<Evidence>()
    var queries = firstQueries
    for (attempt in 1..maxAttempts) {
        tried += queries
        found = (found + search(queries)).distinctBy { it.ref + "#" + it.location + "#" + it.text.take(80) }.take(limit + 4)
        if (judge == null || attempt == maxAttempts) break
        val score = if (found.isEmpty()) 0.0 else judge.sufficient(question, found.take(limit).joinToString("\n\n") { "${it.title}\n${it.text}" })
        if (score >= Judge.THRESHOLD) break
        val next = nextQueries(tried).filter { it !in tried }
        if (next.isEmpty()) break
        emit(AgentEvent.Status("근거 부족 (%.2f) → 다른 검색어로 다시 찾기 ($attempt/$maxAttempts): %s".format(score, next.joinToString(" / "))))
        queries = next
    }
    return found
}
