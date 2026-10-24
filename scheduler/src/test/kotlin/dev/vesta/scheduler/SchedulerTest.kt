package dev.vesta.scheduler

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.RiskLevel
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.schedule.Schedule
import dev.vesta.core.schedule.ScheduleRepository
import dev.vesta.core.session.Session
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.tools.ApprovalDecision
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.cancel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val zone = ZoneId.of("Asia/Seoul")

/** 움직일 수 있는 시계 */
class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId?) = this
    override fun instant() = now
}

class ScheduleStore : ScheduleRepository {
    val items = mutableListOf<Schedule>()
    val runs = mutableListOf<Pair<Long, SessionId>>()
    private var next = 1L
    override suspend fun add(user: UserId, task: String, spec: String, channel: ChannelType, nextAt: Instant?) =
        Schedule(next++, user, task, spec, channel, true, nextAt, null, null, Instant.EPOCH).also { items += it }
    override suspend fun list(user: UserId) = items.filter { it.user == user }
    override suspend fun get(id: Long) = items.firstOrNull { it.id == id }
    override suspend fun delete(user: UserId, id: Long) = items.removeIf { it.id == id && it.user == user }
    override suspend fun setEnabled(user: UserId, id: Long, enabled: Boolean, nextAt: Instant?) =
        update(id) { it.copy(enabled = enabled, nextAt = nextAt) }
    override suspend fun due(now: Instant) = items.filter { it.enabled && it.nextAt != null && !it.nextAt!!.isAfter(now) }.sortedBy { it.nextAt }
    override suspend fun advance(id: Long, nextAt: Instant?, lastAt: Instant?, lastStatus: String?) {
        update(id) { it.copy(nextAt = nextAt, enabled = it.enabled && nextAt != null, lastAt = lastAt ?: it.lastAt, lastStatus = lastStatus ?: it.lastStatus) }
    }
    override suspend fun addRun(id: Long, session: SessionId, at: Instant) { runs += id to session }
    override suspend fun trimRuns(id: Long, keep: Int): List<SessionId> {
        val mine = runs.filter { it.first == id }
        val old = mine.dropLast(keep)
        runs.removeAll(old)
        return old.map { it.second }
    }
    private fun update(id: Long, f: (Schedule) -> Schedule): Boolean {
        val i = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return false
        items[i] = f(items[i]); return true
    }
}

class SessionStore : SessionRepository {
    val items = mutableListOf<Session>()
    val deleted = mutableListOf<SessionId>()
    override suspend fun create(userId: UserId, channel: ChannelType, title: String?) =
        Session(SessionId("s${items.size + 1}"), userId, title, channel, null, Instant.EPOCH, Instant.EPOCH).also { items += it }
    override suspend fun find(userId: UserId, id: SessionId) = items.firstOrNull { it.id == id && it.userId == userId }
    override suspend fun listRecent(userId: UserId, limit: Int) = items.filter { it.userId == userId }.takeLast(limit)
    override suspend fun updateTitle(userId: UserId, id: SessionId, title: String) {}
    override suspend fun delete(userId: UserId, id: SessionId): Boolean { deleted += id; return items.removeIf { it.id == id } }
}

/** 보낸 질문을 적고, [script]대로 답한다 */
class FakeConversation(var script: suspend kotlinx.coroutines.flow.FlowCollector<AgentEvent>.(UserInput) -> Unit = { emit(AgentEvent.Token("답: ${it.text}")) }) : ConversationService {
    val inputs = mutableListOf<Triple<UserContext, SessionId?, UserInput>>()
    val decisions = mutableListOf<ApprovalDecision>()
    override fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent> = flow {
        inputs += Triple(user, sessionId, input)
        script(input)
        emit(AgentEvent.Done(AgentType.CHAT, "m", null))
    }
    override suspend fun resolveApproval(user: UserContext, requestId: String, decision: ApprovalDecision): Boolean { decisions += decision; return true }
}

private val me = UserId(1)
private val owner = UserContext(me, UserRole.OWNER)
/** 2026-10-25 일요일 10:20 (서울) */
private fun at(h: Int, m: Int, day: Int = 25) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant()

class SchedulerTest : FunSpec({
    fun setup(role: UserRole? = UserRole.OWNER): Triple<Scheduler, ScheduleStore, Pair<FakeConversation, TestClock>> {
        val store = ScheduleStore()
        val clock = TestClock(at(10, 20))
        val chat = FakeConversation()
        val scheduler = Scheduler(store, chat, SessionStore(), { role }, clock = clock, zone = zone, keepRuns = 2)
        return Triple(scheduler, store, chat to clock)
    }

    test("만들기·목록·멈춤·다시·지우기, 못 읽으면 예시, 손님은 안 됨") {
        val (scheduler, store, _) = setup()
        scheduler.command(owner, ChannelType.TELEGRAM, "/schedule 매일 8시 오늘 날씨 알려 줘")!!.let {
            it shouldContain "1번 · 매일 오전 8시 · 오늘 날씨 알려 줘"
            it shouldContain "다음 실행: 10월 26일 08:00"
            it shouldContain "대화방"
        }
        store.items.single().let { it.spec shouldBe "daily 08:00"; it.channel shouldBe ChannelType.TELEGRAM; it.nextAt shouldBe at(8, 0, 26) }
        scheduler.command(owner, ChannelType.WEB, "/schedule 날씨")!! shouldContain "언제를 읽지 못했습니다"
        scheduler.command(owner, ChannelType.WEB, "/schedule 10분마다 확인")!! shouldContain "30분~24시간"
        scheduler.command(owner, ChannelType.WEB, "/schedule 매일 9시 /schedule 매일 9시 x")!! shouldContain "예약 명령은"
        scheduler.command(owner, ChannelType.WEB, "/schedule")!! shouldContain "예: /schedule"
        scheduler.command(owner, ChannelType.WEB, "/schedules")!!.let { it shouldContain "1. 매일 오전 8시 · 오늘 날씨 알려 줘 (다음 10월 26일 08:00)" }
        scheduler.command(owner, ChannelType.WEB, "/schedule pause 1")!! shouldContain "멈췄습니다"
        store.items.single().enabled shouldBe false
        scheduler.command(owner, ChannelType.WEB, "/schedule resume 1")!! shouldContain "다음: 10월 26일 08:00"
        scheduler.command(owner, ChannelType.WEB, "/schedule rm 2")!! shouldContain "2번 예약이 없습니다"
        scheduler.command(owner, ChannelType.WEB, "/schedule rm 1")!! shouldContain "지웠습니다"
        store.items shouldBe emptyList()
        scheduler.command(owner, ChannelType.WEB, "오늘 날씨") shouldBe null
        scheduler.command(UserContext(UserId(2), UserRole.GUEST), ChannelType.WEB, "/schedule 매일 8시 x")!! shouldContain "손님"
    }

    test("실행: 때가 되면 새 대화로 사람 없이 보내고, 승인은 거부, 결과를 보내고, 다음 때로. 한 번 예약은 끝나면 꺼짐") {
        val store = ScheduleStore()
        val clock = TestClock(at(10, 20))
        val chat = FakeConversation { input ->
            if ("파일" in input.text) emit(AgentEvent.ApprovalRequested("r1", "write_file", RiskLevel.WRITE, dev.vesta.core.tools.ApprovalDetails("쓰기", "x"), null))
            emit(AgentEvent.Token("답: ${input.text}"))
        }
        val sessions = SessionStore()
        val sent = mutableListOf<String>()
        val scheduler = Scheduler(store, chat, sessions, { UserRole.OWNER }, deliver = { _, t -> sent += t; 1 }, clock = clock, zone = zone, keepRuns = 2)
        scheduler.add(me, ChannelType.TELEGRAM, "매일 11시 오늘 날씨 알려 줘").getOrThrow()
        scheduler.add(me, ChannelType.WEB, "오늘 11시 파일 정리해 줘").getOrThrow()
        scheduler.tick() shouldBe 0
        clock.now = at(11, 0, 25)
        scheduler.tick() shouldBe 2
        chat.inputs.map { it.third.text } shouldBe listOf("오늘 날씨 알려 줘", "파일 정리해 줘")
        chat.inputs.all { it.third.unattended } shouldBe true
        sessions.items.map { it.title } shouldBe listOf("⏰ 오늘 날씨 알려 줘", "⏰ 파일 정리해 줘")
        chat.inputs.map { it.second } shouldBe sessions.items.map { it.id }
        chat.decisions.single() shouldBe ApprovalDecision.Deny(Scheduler.DENY)
        sent[0] shouldStartWith "⏰ 매일 오전 11시 · 오늘 날씨 알려 줘\n\n답: 오늘 날씨 알려 줘"
        sent[1] shouldContain "승인이 필요한 단계는 예약 실행에서 하지 않았습니다"
        store.items[0].let { it.nextAt shouldBe at(11, 0, 26); it.lastAt shouldBe at(11, 0); it.lastStatus!! shouldStartWith "끝 · 답: 오늘" }
        store.items[1].let { it.enabled shouldBe false; it.nextAt shouldBe null; it.lastStatus!! shouldContain "승인 단계 거부" }
        // 실행 대화는 최근 2개만
        clock.now = at(11, 0, 26); scheduler.tick()
        clock.now = at(11, 0, 27); scheduler.tick()
        sessions.deleted shouldBe listOf(SessionId("s1"))
    }

    test("1시간 넘게 놓친 실행은 건너뛰고 다음 때로, 사용자가 없거나 손님이면 끔, 실패·빈 답은 실패로") {
        val (scheduler, store, pair) = setup()
        val (chat, clock) = pair
        scheduler.add(me, ChannelType.WEB, "매일 11시 날씨").getOrThrow()
        clock.now = at(12, 30)
        scheduler.tick() shouldBe 0
        store.items.single().let { it.nextAt shouldBe at(11, 0, 26); it.lastStatus!! shouldContain "놓침: 10월 25일 11:00" }
        chat.inputs shouldBe emptyList()
        chat.script = { emit(AgentEvent.Error("모델 꺼짐")) }
        clock.now = at(11, 30, 26)
        scheduler.tick() shouldBe 1
        store.items.single().lastStatus!! shouldStartWith "실패 · 하지 못했습니다: 모델 꺼짐"

        val (guestRun, guestStore, _) = setup(UserRole.GUEST)
        guestStore.add(me, "x", "every 30m", ChannelType.WEB, at(10, 0))
        guestRun.tick() shouldBe 0
        guestStore.items.single().enabled shouldBe false
    }

    test("지금 실행 (/schedule run): 다음 때는 그대로") {
        val (scheduler, store, pair) = setup()
        scheduler.command(owner, ChannelType.WEB, "/schedule 매일 8시 뉴스 요약")
        scheduler.command(owner, ChannelType.WEB, "/schedule run 1")!! shouldContain "답: 뉴스 요약"
        pair.first.inputs.single().third.text shouldBe "뉴스 요약"
        store.items.single().let { it.nextAt shouldBe at(8, 0, 26); it.lastAt shouldBe at(10, 20) }
    }

    test("지금 실행은 사용자당 하나, 같은 예약은 겹치지 않고, 예약 실행은 기다리지 않는다 (보안 검토)") {
        val store = ScheduleStore()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val chat = FakeConversation { gate.await(); emit(AgentEvent.Token("답")) }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val clock = TestClock(at(10, 20))
        val scheduler = Scheduler(store, chat, SessionStore(), { UserRole.OWNER }, clock = clock, zone = zone, background = scope)
        val a = scheduler.add(me, ChannelType.WEB, "매일 11시 a").getOrThrow()
        val b = scheduler.add(me, ChannelType.WEB, "매일 12시 b").getOrThrow()
        scheduler.runNow(owner, a.id) shouldBe null
        scheduler.runNow(owner, a.id) shouldBe "이 예약은 이미 돌고 있습니다"
        scheduler.runNow(owner, b.id) shouldBe "다른 예약을 지금 돌리는 중입니다. 끝난 뒤 다시 해 주세요"
        scheduler.runNow(UserContext(UserId(9), UserRole.OWNER), a.id) shouldBe "없는 예약입니다"
        // 도는 예약은 때가 와도 겹쳐 돌리지 않고, tick은 기다리지 않는다
        clock.now = at(12, 0)
        kotlinx.coroutines.withTimeout(2_000) { scheduler.tick() } shouldBe 1
        store.items.first { it.id == b.id }.nextAt shouldBe at(12, 0, 26)
        gate.complete(Unit)
        kotlinx.coroutines.withTimeout(5_000) { while (store.items.any { it.lastAt == null }) kotlinx.coroutines.delay(20) }
        scheduler.runNow(owner, b.id) shouldBe null
        scope.cancel()
    }

    test("기다리는 사이 바뀐 것은 잠금 뒤에 다시 본다: 지움·멈춤·손님으로 바뀜이면 돌지 않는다 (보안 검토)") {
        val store = ScheduleStore()
        var role: UserRole? = UserRole.OWNER
        val chat = FakeConversation()
        val scheduler = Scheduler(store, chat, SessionStore(), { role }, clock = TestClock(at(10, 20)), zone = zone)
        val daily = scheduler.add(me, ChannelType.WEB, "매일 11시 a").getOrThrow()
        val once = scheduler.add(me, ChannelType.WEB, "오늘 11시 b").getOrThrow()
        store.setEnabled(me, daily.id, false, null)
        scheduler.run(daily, manual = false) shouldBe null
        (scheduler.run(daily, manual = true) != null) shouldBe true
        store.advance(once.id, null, null, null)
        (scheduler.run(once, manual = false) != null) shouldBe true
        role = UserRole.GUEST
        scheduler.run(once, manual = true) shouldBe null
        role = UserRole.OWNER
        store.delete(me, once.id)
        scheduler.run(once, manual = true) shouldBe null
        chat.inputs.size shouldBe 2
    }

    test("사용자당 개수 한도") {
        val store = ScheduleStore()
        val scheduler = Scheduler(store, FakeConversation(), SessionStore(), { UserRole.OWNER }, clock = TestClock(at(10, 20)), zone = zone, maxPerUser = 2)
        scheduler.add(me, ChannelType.WEB, "매일 8시 a").isSuccess shouldBe true
        scheduler.add(me, ChannelType.WEB, "매일 9시 b").isSuccess shouldBe true
        scheduler.add(me, ChannelType.WEB, "매일 10시 c").exceptionOrNull()!!.message!! shouldContain "2개까지"
    }
})

