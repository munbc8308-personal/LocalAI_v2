package dev.vesta.app

import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.studio.AgentRun
import dev.vesta.core.studio.RunEvent
import dev.vesta.core.studio.RunJournal
import dev.vesta.core.studio.RunRepository
import dev.vesta.core.studio.RunStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 실행 소식 하나: 새 단계, 또는 끝난 실행 (화면에 바로 흘린다) */
data class RunUpdate(val run: Long, val user: UserId, val event: RunEvent? = null, val finished: AgentRun? = null)

/**
 * 실행 기록 (PROJECT.md A-12·A-13, M22). AgentEvent를 사람이 읽는 단계로 바꿔 저장하고 [updates]로 흘린다.
 * 답 글자(Token)는 단계로 남기지 않고 끝에 답 전체로 남긴다. 생각(Reasoning)은 남기지 않는다
 */
class StudioJournal(private val runs: RunRepository) : RunJournal {
    private val flow = MutableSharedFlow<RunUpdate>(extraBufferCapacity = 512)
    val updates: SharedFlow<RunUpdate> = flow
    private val owners = java.util.concurrent.ConcurrentHashMap<Long, UserId>()
    /** 마지막으로 질문을 받거나 답을 끝낸 때 (큐레이터가 쉬는 때를 고를 때, L-25) */
    @Volatile var lastActivity: java.time.Instant = java.time.Instant.EPOCH
        private set
    /** 지금 답하고 있는 질문이 있는가 */
    val busy: Boolean get() = owners.isNotEmpty()

    /** 백그라운드 일 한 번을 실행 하나로 남긴다 (큐레이터 등, L-27): 단계 (종류, 제목, 자세히) */
    suspend fun background(user: UserId, agent: String, input: String, steps: List<Triple<String, String, String?>>) {
        val run = runs.start(user, null, agent, null, input)
        runs.setAgent(run.id, agent, null)
        steps.forEach { (kind, title, detail) -> flow.tryEmit(RunUpdate(run.id, user, runs.add(run.id, kind, title, detail))) }
        runs.finish(run.id, RunStatus.DONE, null)
        flow.tryEmit(RunUpdate(run.id, user, finished = runs.get(run.id)))
    }

    override suspend fun start(user: UserId, session: SessionId?, input: String): Long {
        val run = runs.start(user, session, "CHAT", null, input)
        owners[run.id] = user
        lastActivity = java.time.Instant.now()
        flow.tryEmit(RunUpdate(run.id, user, runs.add(run.id, "input", input.lineSequence().firstOrNull().orEmpty().take(120), input.takeIf { it.length > 120 })))
        return run.id
    }

    override suspend fun event(run: Long, event: AgentEvent) {
        val (kind, title, detail) = describe(event) ?: return
        if (event is AgentEvent.Routed) runs.setAgent(run, if (event.decision.team) TEAM else event.decision.agent.name, event.decision.customAgent)
        val saved = runs.add(run, kind, title, detail)
        flow.tryEmit(RunUpdate(run, owners[run] ?: return, saved))
    }

    override suspend fun finish(run: Long, status: RunStatus, answer: String?) {
        answer?.let { runs.add(run, "answer", it.lineSequence().firstOrNull().orEmpty().take(120), it) }
            ?.let { e -> owners[run]?.let { flow.tryEmit(RunUpdate(run, it, e)) } }
        runs.finish(run, status, answer)
        lastActivity = java.time.Instant.now()
        val user = owners.remove(run) ?: return
        flow.tryEmit(RunUpdate(run, user, finished = runs.get(run)))
    }

    private fun describe(event: AgentEvent): Triple<String, String, String?>? = when (event) {
        is AgentEvent.Routed -> Triple(
            "routed",
            "담당: " + (event.decision.customAgent?.let { if (event.decision.team) "$it (팀)" else "$it (사용자 정의 에이전트)" }
                ?: event.decision.skill?.let { "스킬 /$it · ${event.decision.agent.name}" } ?: event.decision.agent.name) +
                " · ${event.decision.stage.name.lowercase()} ${"%.2f".format(event.decision.confidence)}",
            event.decision.collections.takeIf { it.isNotEmpty() }?.joinToString(prefix = "컬렉션: "),
        )
        is AgentEvent.Status -> Triple("status", event.text, null)
        // 나눠 맡기기(팀): 그 사이의 단계는 멤버가 한 일 (타임라인에서 들여 쓴다)
        is AgentEvent.ToolStarted -> if (event.tool == DELEGATE) Triple("delegate_started", "나눠 맡김: ${event.summary}".take(300), null)
            else Triple("tool_started", "도구 ${event.tool} 시작", event.summary)
        is AgentEvent.ToolFinished -> if (event.tool == DELEGATE) Triple("delegate_finished", (if (event.ok) "받음: " else "실패: ") + event.summary, null)
            else Triple("tool_finished", "도구 ${event.tool} " + if (event.ok) "끝" else "실패", event.summary)
        is AgentEvent.ApprovalRequested -> Triple("approval", "승인 요청: ${event.details.title}", listOfNotNull(event.details.warning?.let { "⚠ $it" }, event.details.body, event.reason?.let { "이유: $it" }).joinToString("\n"))
        is AgentEvent.Sources -> Triple("sources", "자료 ${event.evidence.size}개", event.evidence.take(10).joinToString("\n") { "· ${it.title} ${it.location.orEmpty()}" })
        is AgentEvent.Grounding -> Triple("grounding", "근거 확인: ${event.checked}문장 중 미확인 ${event.unsupported.size}", event.unsupported.joinToString("\n").ifBlank { null })
        is AgentEvent.Done -> Triple("done", "끝 · ${event.model}" + (event.usage?.completionTokens?.let { " · $it 토큰" } ?: ""), null)
        is AgentEvent.Error -> Triple("error", "오류", event.message)
        is AgentEvent.Media -> Triple("media", "이미지 만듦", event.caption)
        else -> null
    }

    private companion object {
        const val TEAM = "TEAM"
        const val DELEGATE = "delegate"
    }
}
