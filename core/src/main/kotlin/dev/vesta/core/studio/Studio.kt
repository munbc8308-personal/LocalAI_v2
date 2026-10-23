package dev.vesta.core.studio

import dev.vesta.core.ModelRole
import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import java.time.Instant

/**
 * 사용자 정의 에이전트 (PROJECT.md A-11, M22). 작업 에이전트(TaskAgent) 위에 역할·지침·도구 목록을 얹는다.
 * [tools]는 이 에이전트가 쓸 도구 이름: 실제로는 그 사용자 역할이 쓸 수 있는 도구와 겹치는 것만 쓴다 (권한을 넓히지 못한다)
 */
data class CustomAgentDef(
    val owner: UserId,
    val name: String,
    val description: String,
    val instructions: String,
    val model: ModelRole = ModelRole.GENERAL,
    val tools: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val updatedAt: Instant = Instant.EPOCH,
) {
    companion object {
        /** `/agent 이름` 으로 부르므로 공백 없는 짧은 이름 */
        val NAME = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,31}$")
        const val MAX_INSTRUCTIONS = 8_000
        const val MAX_DESCRIPTION = 300
    }
}

interface CustomAgentRepository {
    suspend fun list(owner: UserId): List<CustomAgentDef>
    suspend fun find(owner: UserId, name: String): CustomAgentDef?
    /** 이름이 같으면 고친다 */
    suspend fun save(def: CustomAgentDef): CustomAgentDef
    suspend fun delete(owner: UserId, name: String): Boolean
}

enum class RunStatus { RUNNING, DONE, FAILED, CANCELLED }

/** 실행 한 번 (A-12): 질문 하나에 에이전트가 답할 때까지. [customAgent]는 사용자 정의 에이전트 이름 */
data class AgentRun(
    val id: Long,
    val user: UserId,
    val session: SessionId?,
    val agent: String,
    val customAgent: String?,
    val input: String,
    val status: RunStatus,
    val answer: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

/** 실행의 한 단계 (A-12). [kind]: routed·status·tool_started·tool_finished·approval·sources·grounding·done·error·answer */
data class RunEvent(val run: Long, val seq: Int, val at: Instant, val kind: String, val title: String, val detail: String?)

interface RunRepository {
    suspend fun start(user: UserId, session: SessionId?, agent: String, customAgent: String?, input: String): AgentRun
    suspend fun add(run: Long, kind: String, title: String, detail: String?): RunEvent
    suspend fun setAgent(run: Long, agent: String, customAgent: String?)
    suspend fun finish(run: Long, status: RunStatus, answer: String?)
    /** [user]가 null이면 모든 사용자 (Mac 주인) */
    suspend fun list(user: UserId?, limit: Int): List<AgentRun>
    suspend fun get(id: Long): AgentRun?
    suspend fun events(run: Long, afterSeq: Int = 0): List<RunEvent>
    /** 서버가 꺼지면서 끝나지 못한 실행 정리 */
    suspend fun abandonRunning(): Int
}

/**
 * 실행 기록 + 실시간 알림 (A-12·A-13). 대화 서비스가 질문마다 [start]하고, 내보내는 이벤트를 [event]로 넘긴다.
 * 구현이 AgentEvent를 사람이 읽는 단계(RunEvent)로 바꿔 저장하고, 보고 있는 화면에 바로 흘린다
 */
interface RunJournal {
    suspend fun start(user: UserId, session: SessionId?, input: String): Long
    suspend fun event(run: Long, event: dev.vesta.core.agent.AgentEvent)
    suspend fun finish(run: Long, status: RunStatus, answer: String?)
}

/** `/agent 이름 할 일` (A-16) */
data class CustomCall(val name: String, val task: String) {
    companion object {
        private val PATTERN = Regex("^[/!]agent\\s+(\\S+)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
        fun parse(text: String): CustomCall? = PATTERN.find(text.trim())?.let { CustomCall(it.groupValues[1], it.groupValues[2].trim()) }
    }
}
