package dev.vesta.agents

import dev.vesta.core.agent.AgentEvent
import dev.vesta.decision.Judge
import dev.vesta.decision.Sentences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * D7 근거 검증 (PROJECT.md 5.1 [6]). 답변이 끝나면 문장마다 근거로 뒷받침되는지 판정해 Grounding 이벤트를 낸다.
 * 답변 자체는 바꾸지 않는다 (사용자에게 확인 안 된 문장을 보여 줄 뿐). [judge]가 없으면 그대로 흘려보낸다.
 */
internal fun Flow<AgentEvent>.verifiedAgainst(judge: Judge?, evidence: String): Flow<AgentEvent> {
    if (judge == null || evidence.isBlank()) return this
    val upstream = this
    return flow {
        val answer = StringBuilder()
        upstream.collect { event ->
            when (event) {
                is AgentEvent.Token -> { answer.append(event.text); emit(event) }
                is AgentEvent.Done -> {
                    val claims = Sentences.claims(answer.toString())
                    if (claims.isNotEmpty()) {
                        emit(AgentEvent.Status("근거 확인 중 (${claims.size}문장)"))
                        val scores = judge.grounded(evidence, claims)
                        emit(AgentEvent.Grounding(claims.size, claims.filterIndexed { i, _ -> scores[i] < Judge.THRESHOLD }))
                    }
                    emit(event)
                }
                else -> emit(event)
            }
        }
    }
}
