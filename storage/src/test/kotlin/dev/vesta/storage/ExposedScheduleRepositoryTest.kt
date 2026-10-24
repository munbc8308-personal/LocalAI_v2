package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.createTempDirectory

class ExposedScheduleRepositoryTest : FunSpec({
    test("예약 (M29): 때가 된 것만 이른 순, 다음 때가 없으면 꺼짐, 본인 것만 지움, 실행 대화는 최근 것만") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val repo = ExposedScheduleRepository(db.db)
        val t0 = Instant.parse("2026-10-25T00:00:00Z")
        val a = repo.add(owner, "날씨", "daily 08:00", ChannelType.TELEGRAM, t0.plusSeconds(60))
        val b = repo.add(owner, "뉴스", "once 2026-10-25T09:00", ChannelType.WEB, t0)
        repo.due(t0).map { it.id } shouldBe listOf(b.id)
        repo.due(t0.plusSeconds(60)).map { it.id } shouldBe listOf(b.id, a.id)
        repo.advance(b.id, null, t0, "끝 · 답")
        repo.get(b.id)!!.let { it.enabled shouldBe false; it.lastStatus shouldBe "끝 · 답"; it.lastAt shouldBe t0 }
        repo.setEnabled(owner, a.id, false, null) shouldBe true
        repo.due(t0.plusSeconds(3600)) shouldBe emptyList()
        repo.get(a.id)!!.channel shouldBe ChannelType.TELEGRAM
        (1..4).forEach { repo.addRun(a.id, SessionId("s$it"), t0) }
        repo.trimRuns(a.id, 2) shouldBe listOf(SessionId("s2"), SessionId("s1"))
        repo.trimRuns(a.id, 2) shouldBe emptyList()
        repo.delete(dev.vesta.core.UserId(owner.value + 99), a.id) shouldBe false
        repo.delete(owner, a.id) shouldBe true
        repo.list(owner).map { it.id } shouldBe listOf(b.id)
    }
})
