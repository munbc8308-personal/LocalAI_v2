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

/**
 * 팀 (A-14, M22): 리더 하나 + 멤버들. 모두 같은 사용자의 사용자 정의 에이전트.
 * 리더가 `delegate` 도구로 멤버에게 일을 주고, 멤버는 따로 맥락에서 일한 뒤 답만 돌려준다. 멤버는 다시 나눠 맡기지 않는다 (한 단계)
 */
data class TeamDef(
    val owner: UserId,
    val name: String,
    val description: String,
    val leader: String,
    val members: List<String>,
    val updatedAt: Instant = Instant.EPOCH,
    /** 사무실 화면의 팀 방 꾸미기 (M38 OF-10): [THEMES] 중 하나, null이면 팀 이름으로 정한다 */
    val theme: String? = null,
    /** 이 팀이 맡을 질문 예시 (M41 팀 자동 배정: 의미 비교에 쓴다). 한 줄에 하나 */
    val examples: List<String> = emptyList(),
) {
    companion object {
        const val MAX_MEMBERS = 6
        const val MAX_EXAMPLES = 8
        const val MAX_EXAMPLE_CHARS = 200

        /** 빈 줄·긴 줄을 정리한 예시 */
        fun cleanExamples(lines: List<String>) = lines.map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(MAX_EXAMPLE_CHARS) }.distinct().take(MAX_EXAMPLES)
        val THEMES = listOf("office", "cafe", "lab", "library", "studio", "factory")
    }
}

interface TeamRepository {
    suspend fun list(owner: UserId): List<TeamDef>
    suspend fun find(owner: UserId, name: String): TeamDef?
    /** 이름이 같으면 고친다 */
    suspend fun save(def: TeamDef): TeamDef
    suspend fun delete(owner: UserId, name: String): Boolean
}

/**
 * A2A 에이전트 (A-15, M22): 표준 프로토콜(Agent2Agent, JSON-RPC)로 붙는 바깥 에이전트. Mac 주인만 등록·사용한다.
 * [name]은 이 Mac에서 부르는 이름(`/agent 이름`, 팀 멤버), 나머지는 등록할 때 읽은 Agent Card 내용.
 * 인증 토큰은 여기 두지 않고 키체인(`dev.vesta.a2a`, 계정 = [name])에 둔다
 */
data class A2aAgentDef(
    val owner: UserId,
    val name: String,
    val cardUrl: String,
    /** JSON-RPC 주소 */
    val endpoint: String,
    /** "1.0" 또는 "0.3" */
    val protocol: String,
    val displayName: String,
    val description: String,
    val skills: List<String> = emptyList(),
    val streaming: Boolean = false,
    /** Agent Card가 인증을 요구하는지 */
    val auth: Boolean = false,
    /** 사용자가 "이 Mac·내 네트워크의 에이전트"라고 고른 내부 에이전트인가. 아니면 내부 주소에는 연결하지 않는다 (연결마다 DNS 검사) */
    val local: Boolean = false,
    val addedAt: Instant = Instant.EPOCH,
)

interface A2aAgentRepository {
    suspend fun list(owner: UserId): List<A2aAgentDef>
    suspend fun find(owner: UserId, name: String): A2aAgentDef?
    /** 이름이 같으면 고친다 (카드 다시 읽기) */
    suspend fun save(def: A2aAgentDef): A2aAgentDef
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
    /** [user]의 실행 하나와 그 단계를 지운다. 아직 도는 실행은 지우지 않는다 */
    suspend fun delete(user: UserId, id: Long): Boolean = false
    /** [user]의 끝난 실행을 모두 지운다. 지운 수 */
    suspend fun deleteFinished(user: UserId): Int = 0
    /** ID가 [after]보다 큰 실행, 오래된 것부터 (스킬 제안이 차례로 볼 때, M28). 아직 도는 것도 포함 */
    suspend fun after(after: Long, limit: Int): List<AgentRun> = emptyList()
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

/** `/agent 이름 할 일`, 팀은 `/team 이름 할 일` (A-16) */
data class CustomCall(val name: String, val task: String, val team: Boolean = false) {
    companion object {
        private val PATTERN = Regex("^[/!](agent|team)\\s+(\\S+)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
        fun parse(text: String): CustomCall? = PATTERN.find(text.trim())?.let { CustomCall(it.groupValues[2], it.groupValues[3].trim(), it.groupValues[1] == "team") }
    }
}
