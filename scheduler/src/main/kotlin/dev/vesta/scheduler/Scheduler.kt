package dev.vesta.scheduler

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.schedule.Schedule
import dev.vesta.core.schedule.ScheduleRepository
import dev.vesta.core.schedule.When
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.tools.ApprovalDecision
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/** 예약 실행 한 번의 결과 */
/** [summary]는 마지막 결과 한 줄 (답의 마지막 줄: 앞머리 "…할게"보다 결론이 끝에 온다) */
data class ScheduleOutcome(val session: SessionId, val text: String, val ok: Boolean, val denied: Boolean, val summary: String = "")

/**
 * 예약 작업 (PROJECT.md 7.23, M29).
 * - 명령 (S-01·S-04): `/schedule <언제> <할 일>`, `/schedules`, `/schedule rm|pause|resume|run 번호`. 모델 없이 답한다
 * - 실행 (S-02·S-03): [start]가 30초마다 때가 된 예약을 하나씩 돈다. 실행마다 새 대화를 만들어 그 사용자로 질문을 보내고(사람이 없는 실행),
 *   답을 [deliver]로 만든 곳에 보낸다. 승인 요청은 바로 거부한다 (S-05)
 */
class Scheduler(
    private val repo: ScheduleRepository,
    private val conversation: ConversationService,
    private val sessions: SessionRepository,
    /** 지금 이 사용자의 역할 (없는 사용자면 null) */
    private val roleOf: suspend (UserId) -> UserRole?,
    /** 결과를 만든 곳으로 (메신저면 연결된 대화방에). 보낸 대화방 수 */
    private val deliver: suspend (Schedule, String) -> Int = { _, _ -> 0 },
    /** 오래된 실행 대화 지우기 (미디어 포함) */
    private val deleteSession: suspend (UserId, SessionId) -> Unit = { u, s -> sessions.delete(u, s) },
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxPerUser: Int = 20,
    private val keepRuns: Int = 10,
    private val timeout: Duration = Duration.ofMinutes(15),
    /** `/schedule run`을 기다리지 않고 돌릴 곳. 없으면 그 자리에서 돌고 결과를 답한다 */
    private val background: CoroutineScope? = null,
) {
    private val lock = Mutex()

    fun start(scope: CoroutineScope, every: Duration = Duration.ofSeconds(30)): Job = scope.launch {
        while (isActive) {
            runCatching { tick() }.onFailure { log.warn(it) { "예약 실행 실패" } }
            delay(every.toMillis())
        }
    }

    /** 때가 된 예약을 차례로. 1시간 넘게 놓친 것은 건너뛰고 다음 때로 (S-05). 돈 예약 수 */
    suspend fun tick(): Int {
        var ran = 0
        for (s in repo.due(clock.instant())) {
            val now = clock.instant()
            val spec = s.parsed
            if (spec == null) { repo.advance(s.id, null, null, "읽을 수 없는 예약이라 껐습니다"); continue }
            val next = spec.next(now.atZone(zone))?.toInstant()
            val role = roleOf(s.user)
            if (role == null || role == UserRole.GUEST) { repo.advance(s.id, null, null, "이 계정은 예약을 쓸 수 없어 껐습니다"); continue }
            val planned = s.nextAt ?: now
            if (Duration.between(planned, now) > LATE) {
                repo.advance(s.id, next, null, "놓침: ${local(planned)} 실행을 건너뜀 (서버가 꺼져 있었음)")
                continue
            }
            val outcome = run(s, role)
            repo.advance(s.id, next, now, status(outcome))
            ran++
        }
        return ran
    }

    /** 예약 하나를 지금 돈다 (때와 상관없이, `/schedule run`) */
    suspend fun run(s: Schedule, role: UserRole): ScheduleOutcome = lock.withLock {
        val user = UserContext(s.user, role)
        val session = sessions.create(s.user, s.channel, "⏰ " + s.task.take(40))
        repo.addRun(s.id, session.id, clock.instant())
        val answer = StringBuilder()
        var error: String? = null
        var denied = false
        val finished = withTimeoutOrNull(timeout.toMillis()) {
            conversation.send(user, s.channel, session.id, UserInput(s.task, unattended = true)).collect { e ->
                when (e) {
                    is AgentEvent.Token -> answer.append(e.text)
                    is AgentEvent.ApprovalRequested -> { denied = true; conversation.resolveApproval(user, e.requestId, ApprovalDecision.Deny(DENY)) }
                    is AgentEvent.Error -> error = e.message
                    else -> Unit
                }
            }
        } != null
        val ok = finished && error == null && answer.isNotBlank()
        val body = when {
            !finished -> answer.toString().trim().ifEmpty { "" } + "\n\n(${timeout.toMinutes()}분 안에 끝나지 않아 멈췄습니다)"
            error != null -> "하지 못했습니다: $error"
            answer.isBlank() -> "답이 비었습니다."
            else -> answer.toString().trim()
        }.trim()
        val text = "⏰ ${s.parsed?.describe().orEmpty()} · ${s.task}\n\n$body" +
            if (denied) "\n\n(승인이 필요한 단계는 예약 실행에서 하지 않았습니다. 직접 대화에서 시켜 주세요.)" else ""
        runCatching { deliver(s, text) }.onFailure { log.warn(it) { "예약 결과 보내기 실패: ${s.id}" } }
        repo.trimRuns(s.id, keepRuns).forEach { old -> runCatching { deleteSession(s.user, old) } }
        ScheduleOutcome(session.id, text, ok, denied, body.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().trim())
    }

    /** 대화 명령 (S-01·S-04). 예약 명령이 아니면 null */
    suspend fun command(user: UserContext, channel: ChannelType, text: String): String? {
        val t = text.trim()
        if (LIST.matches(t)) return list(user.userId)
        val m = COMMAND.find(t) ?: return null
        if (user.role == UserRole.GUEST) return "손님 계정은 예약을 만들 수 없습니다."
        val rest = m.groupValues[1].trim()
        ACTION.find(rest)?.let { a -> return action(user, a.groupValues[1].lowercase(), a.groupValues[2].toInt()) }
        if (rest.isEmpty()) return HELP
        return add(user.userId, channel, rest).fold({ "예약했습니다: ${it.number(user.userId)}" }, { it.message ?: "예약하지 못했습니다" })
    }

    /** 예약 만들기. 실패면 사람이 읽는 이유 */
    suspend fun add(user: UserId, channel: ChannelType, text: String): Result<Schedule> {
        val now = clock.instant().atZone(zone)
        val parsed = When.parse(text, now) ?: return Result.failure(IllegalArgumentException("언제를 읽지 못했습니다.\n$HELP"))
        val spec = parsed.spec ?: return Result.failure(IllegalArgumentException(parsed.problem ?: "언제를 읽지 못했습니다"))
        val task = parsed.task
        if (task.isBlank()) return Result.failure(IllegalArgumentException("할 일을 적어 주세요. 예: /schedule 매일 8시 오늘 날씨 알려 줘"))
        if (task.length > MAX_TASK) return Result.failure(IllegalArgumentException("할 일은 ${MAX_TASK}자까지입니다"))
        if (COMMAND.containsMatchIn(task) || LIST.matches(task)) return Result.failure(IllegalArgumentException("예약 안에서 예약 명령은 쓸 수 없습니다"))
        if (repo.list(user).size >= maxPerUser) return Result.failure(IllegalArgumentException("예약은 ${maxPerUser}개까지입니다. /schedules 에서 지우고 다시 만들어 주세요"))
        return Result.success(repo.add(user, task, spec.canonical, channel, spec.next(now)?.toInstant()))
    }

    /** 켜기·끄기 (웹·CLI). 켤 때 다음 때가 없으면(지난 한 번 예약) false */
    suspend fun setEnabled(user: UserId, id: Long, on: Boolean): Boolean {
        val s = repo.get(id)?.takeIf { it.user == user } ?: return false
        if (!on) return repo.setEnabled(user, id, false, null)
        val next = s.parsed?.next(clock.instant().atZone(zone))?.toInstant() ?: return false
        return repo.setEnabled(user, id, true, next)
    }

    /** 지금 돌리기 (웹·CLI): 기다리지 않는다. 다음 때는 그대로 */
    suspend fun runNow(user: UserContext, id: Long): Boolean {
        val s = repo.get(id)?.takeIf { it.user == user.userId } ?: return false
        if (user.role == UserRole.GUEST) return false
        val work = suspend { repo.advance(s.id, s.nextAt, clock.instant(), status(run(s, user.role))) }
        background?.launch { runCatching { work() }.onFailure { log.warn(it) { "예약 지금 실행 실패: ${s.id}" } } } ?: work()
        return true
    }

    /** 사람이 읽는 언제 (웹 목록) */
    fun describe(s: Schedule): String = s.parsed?.describe() ?: s.spec

    private suspend fun action(user: UserContext, verb: String, number: Int): String {
        val s = repo.list(user.userId).getOrNull(number - 1) ?: return "${number}번 예약이 없습니다. /schedules 로 목록을 볼 수 있습니다."
        val now = clock.instant().atZone(zone)
        return when (verb) {
            "rm", "delete" -> { repo.delete(user.userId, s.id); "${number}번 예약을 지웠습니다: ${s.task}" }
            "pause" -> { repo.setEnabled(user.userId, s.id, false, null); "${number}번 예약을 멈췄습니다. 다시 하려면 /schedule resume $number" }
            "resume" -> {
                val next = s.parsed?.next(now)?.toInstant() ?: return "${number}번 예약은 다시 돌 때가 없습니다 (지난 한 번 예약)."
                repo.setEnabled(user.userId, s.id, true, next); "${number}번 예약을 다시 켰습니다. 다음: ${local(next)}"
            }
            else -> { // run
                suspend fun now(): String {
                    val outcome = run(s, user.role)
                    repo.advance(s.id, s.nextAt, clock.instant(), status(outcome))
                    return outcome.text
                }
                val scope = background ?: return now()
                scope.launch { runCatching { now() }.onFailure { log.warn(it) { "예약 지금 실행 실패: ${s.id}" } } }
                "${number}번 예약을 지금 돌립니다. 결과는 새 대화(⏰)" + (if (s.channel == ChannelType.WEB || s.channel == ChannelType.CLI) "" else "와 이 대화방") + "으로 갑니다."
            }
        }
    }

    private suspend fun list(user: UserId): String {
        val all = repo.list(user)
        if (all.isEmpty()) return "예약이 없습니다.\n$HELP"
        return all.mapIndexed { i, s ->
            val state = if (!s.enabled) "멈춤" else s.nextAt?.let { "다음 ${local(it)}" } ?: "끝남"
            "${i + 1}. ${s.parsed?.describe() ?: s.spec} · ${s.task} ($state" + (s.lastStatus?.let { " · 마지막: $it" } ?: "") + ")"
        }.joinToString("\n", prefix = "예약 ${all.size}개:\n", postfix = "\n\n지우기 /schedule rm 번호 · 멈춤 pause · 다시 resume · 지금 run")
    }

    private suspend fun Schedule.number(user: UserId): String {
        val n = repo.list(user).indexOfFirst { it.id == id } + 1
        return "${n}번 · ${parsed?.describe() ?: spec} · $task\n다음 실행: ${nextAt?.let { local(it) } ?: "없음"}. 결과는 이 " +
            (if (channel == ChannelType.WEB || channel == ChannelType.CLI) "웹 대화 목록(⏰)" else "대화방") + "으로 갑니다. 쓰기·셸 같은 승인 단계는 예약 실행에서 하지 않습니다."
    }

    private fun status(o: ScheduleOutcome) = (if (o.ok) "끝" else "실패") + (if (o.denied) " · 승인 단계 거부" else "") + " · " + o.summary.take(60)

    private fun local(at: Instant) = at.atZone(zone).format(FORMAT)

    companion object {
        const val MAX_TASK = 1_000
        /** 이만큼 넘게 놓친 실행은 건너뛴다 (S-05) */
        val LATE: Duration = Duration.ofHours(1)
        const val DENY = "예약 실행은 승인이 필요한 단계를 하지 않습니다"
        private val FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
        private val LIST = Regex("^[/!]schedules\\s*$", RegexOption.IGNORE_CASE)
        private val COMMAND = Regex("^[/!]schedule(?:\\s+(.*))?$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ACTION = Regex("^(rm|delete|pause|resume|run)\\s+(\\d{1,3})\\s*$", RegexOption.IGNORE_CASE)
        val HELP = """
            예: /schedule 매일 8시 오늘 날씨와 일정 알려 줘
            언제: 매일 8시 · 매일 오후 6시 30분 · 평일 7시 · 주말 10시 · 매주 월,수 9시 · 매달 1일 9시 · 3시간마다 · 내일 9시 · 10월 30일 14시
            목록 /schedules · 지우기 /schedule rm 번호 · 멈춤 pause · 다시 resume · 지금 run
        """.trimIndent()
    }
}
