package dev.vesta.server

import dev.vesta.channels.ChannelButton
import dev.vesta.channels.ChannelNotifier
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.config.TunedSettings
import dev.vesta.core.evolution.EvolutionStatus
import dev.vesta.core.evolution.ProposalKind
import dev.vesta.core.evolution.ProposalStatus
import dev.vesta.evolution.EvolutionService
import dev.vesta.storage.ExposedEvolutionRepository
import dev.vesta.storage.VestaDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory

class EvolutionDeskTest : FunSpec({
    class Sent(val user: UserId, val key: String, val text: String, val buttons: List<ChannelButton>)
    val sent = mutableListOf<Sent>()
    val notifier = object : ChannelNotifier {
        override val name = "가짜"
        override val channel = dev.vesta.core.ChannelType.TELEGRAM
        override suspend fun notify(user: UserId, key: String, text: String, buttons: List<ChannelButton>, exact: Boolean): Int { sent += Sent(user, key, text, buttons); return 1 }
    }
    val owner = UserId(1)

    test("점검 결과·제안을 주인에게 한 번씩 알리고, 버튼으로 승인하면 설정을 쓰고 하던 일이 끝난 뒤에만 다시 시작한다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val tuned = createTempDirectory("vesta").resolve("tuned.properties")
        val repo = ExposedEvolutionRepository(db.db)
        val service = EvolutionService(repo, tuned)
        var busy = true
        var exits = 0
        val desk = EvolutionDesk(repo, service, owner, enabled = true, tunedFile = tuned, notifiers = { listOf(notifier) }, busy = { busy }, underLaunchd = true, exit = { exits++ })

        val run = repo.startRun()
        val proposal = service.propose(run.id, ProposalKind.MODEL, "메인 모델 교체: a → b", "종합: 80% → 85%", mapOf("models.general.name" to "b:1"))
        repo.finishRun(run.id, EvolutionStatus.DONE, "## 주간 점검\n지금 모델 **a**: 종합 80%", null)
        val tuning = service.applyNow(run.id, ProposalKind.TUNING, "임계값 조정", "정확도 90% → 95%", mapOf("decision.thresholds.agent" to "0.55"))

        desk.deliver()
        sent.map { it.key } shouldBe listOf("run:${run.id}", "ev:${proposal.id}", "ev:${tuning.id}")
        sent.all { it.user == owner } shouldBe true
        sent[0].text shouldBe "주간 점검\n지금 모델 a: 종합 80%"
        sent[1].buttons.map { it.data } shouldBe listOf("ev:${proposal.id}:a", "ev:${proposal.id}:r")
        sent[2].buttons.map { it.data } shouldBe listOf("ev:${tuning.id}:u")
        sent.clear()
        desk.deliver()
        sent.shouldBeEmpty()

        // 서버가 뜬 뒤 점검이 임계값을 반영했다 → 다시 시작 대기
        desk.restartPending() shouldBe true
        // (다시 띄운 서버라고 치고) 새로 읽은 뒤 승인으로 또 바뀌면, 하던 일이 끝난 뒤에만 다시 시작한다
        val fresh = EvolutionDesk(repo, service, owner, true, tuned, { listOf(notifier) }, busy = { busy }, underLaunchd = true, exit = { exits++ })
        desk.onButton(UserContext(UserId(2), UserRole.MEMBER), "ev:${proposal.id}:a") shouldContain "Mac 주인만"
        desk.onButton(UserContext(owner, UserRole.OWNER), "ev:${proposal.id}:a") shouldContain "반영했습니다"
        repo.proposal(proposal.id)!!.status shouldBe ProposalStatus.APPLIED
        TunedSettings.read(tuned)["models.general.name"] shouldBe "b:1"
        fresh.restartPending() shouldBe true
        fresh.maybeRestart()
        exits shouldBe 0
        busy = false
        fresh.maybeRestart()
        exits shouldBe 1

        // 되돌리기
        desk.onButton(UserContext(owner, UserRole.OWNER), "ev:${tuning.id}:u") shouldContain "되돌렸습니다"
        TunedSettings.read(tuned) shouldBe mapOf("models.general.name" to "b:1")
        desk.state().proposals.map { it.status } shouldBe listOf("ROLLED_BACK", "APPLIED")
    }
})
