package dev.vesta.a2a

import dev.vesta.core.AgentType
import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.studio.A2aAgentDef
import dev.vesta.core.tools.ApprovalBroker
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * A2A 에이전트를 Vesta 에이전트로 (A-15, M22): `/agent 이름 할 일`과 팀 멤버로 쓴다.
 * - 보내는 것은 마지막 질문(할 일) 하나뿐. 대화 기록·파일은 보내지 않는다
 * - 무엇을 어디로 보냈는지 첫 상태 줄로 남긴다 (스튜디오 타임라인)
 * - 로컬 데이터를 읽은 흐름에서 온 요청([AgentRequest.tainted], 예: 파일을 읽은 리더가 맡긴 일)은 보내기 전에 승인을 받는다
 */
class A2aAgent(
    private val def: A2aAgentDef,
    private val client: A2aClient,
    private val approvals: ApprovalBroker,
    private val token: () -> String?,
    private val approvalTimeoutSeconds: Long = 300,
) : Agent {
    override val type = AgentType.TASK

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val model = "a2a:${def.name}"
        val text = request.history.lastOrNull()?.content?.trim().orEmpty()
        if (text.isEmpty()) {
            emit(AgentEvent.Token("보낼 할 일이 없습니다. `/agent ${def.name} 할 일`처럼 적어 주세요."))
            emit(AgentEvent.Done(type, model, null))
            return@flow
        }
        val host = runCatching { java.net.URI(def.endpoint).host }.getOrNull() ?: def.endpoint
        if (request.tainted) {
            val pending = approvals.open(request.user)
            emit(
                AgentEvent.ApprovalRequested(
                    requestId = pending.id,
                    tool = "a2a_send",
                    risk = RiskLevel.NETWORK,
                    details = ApprovalDetails(
                        "A2A ${def.name}(${host})에게 보내기 (${text.length}자)",
                        text,
                        warning = "로컬 데이터를 읽은 뒤의 요청입니다. 이 내용 전부가 바깥 에이전트 $host 로 나갑니다",
                    ),
                    reason = null,
                ),
            )
            val decision = withTimeoutOrNull(approvalTimeoutSeconds.seconds) { pending.result.await() } ?: ApprovalDecision.Deny("응답 없음")
            approvals.close(pending.id)
            if (decision is ApprovalDecision.Deny) {
                emit(AgentEvent.Token("A2A ${def.name}에게 보내지 않았습니다 (승인 거부: ${decision.reason})."))
                emit(AgentEvent.Done(type, model, null))
                return@flow
            }
        }
        emit(AgentEvent.Status("A2A ${def.name}(${host})에게 보냄 ${text.length}자: ${text.lineSequence().first().take(120)}"))
        val card = A2aCard(def.cardUrl, def.displayName, def.description, def.endpoint, def.protocol, def.streaming, def.auth, def.skills, null)
        var answered = false
        try {
            client.send(card, text, token()).collect { update ->
                when (update) {
                    is A2aUpdate.Status -> emit(AgentEvent.Status("${def.name}: " + (update.text?.lineSequence()?.first()?.take(200) ?: stateLabel(update.state))))
                    is A2aUpdate.Text -> { answered = true; emit(AgentEvent.Token(update.text)) }
                    is A2aUpdate.Finished -> when (update.state) {
                        "completed" -> Unit
                        "input-required" -> emit(AgentEvent.Token((if (answered) "\n\n" else "") + "(${def.name}이 추가 입력을 요청했습니다. 이어서 답하려면 다시 맡겨 주세요.)"))
                        "auth-required" -> emit(AgentEvent.Error("${def.name}: 인증이 필요합니다 (키체인 dev.vesta.a2a / ${def.name})"))
                        else -> emit(AgentEvent.Error("${def.name}: ${stateLabel(update.state)}" + (update.text?.let { " — ${it.take(300)}" } ?: "")))
                    }
                }
            }
        } catch (e: A2aException) {
            emit(AgentEvent.Error("A2A ${def.name}: ${e.message}"))
            return@flow
        } catch (e: java.io.IOException) {
            emit(AgentEvent.Error("A2A ${def.name} 연결 실패: ${e.message}"))
            return@flow
        }
        emit(AgentEvent.Done(type, model, null))
    }

    private fun stateLabel(state: String) = when (state) {
        "submitted" -> "접수"
        "working" -> "작업 중"
        "failed" -> "실패"
        "canceled" -> "취소됨"
        "rejected" -> "거절함"
        else -> state
    }
}
