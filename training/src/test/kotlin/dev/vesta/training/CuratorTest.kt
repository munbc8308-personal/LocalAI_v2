package dev.vesta.training

import dev.vesta.core.UserId
import dev.vesta.core.training.CuratorSource
import dev.vesta.core.training.CuratorTurn
import dev.vesta.core.training.SampleKind
import dev.vesta.core.training.SampleSignal
import dev.vesta.core.training.SampleStatus
import dev.vesta.core.training.TrainingSample
import dev.vesta.core.training.TrainingSampleRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

private val me = UserId(1)
private val t0 = Instant.parse("2026-10-23T00:00:00Z")

private fun turn(id: Long, q: String, agent: String? = "CHAT", stage: String? = "DECISION_MODEL", minutes: Long = id, session: String = "s", denied: List<String> = emptyList()) =
    CuratorTurn(me, session, id, q, t0.plusSeconds(minutes * 60), answerId = id * 100, answer = "답 $id", answerAgent = agent, routedStage = stage, deniedTools = denied)

internal class MemorySamples : TrainingSampleRepository {
    val items = mutableListOf<TrainingSample>()
    var at = 0L
    override suspend fun add(sample: TrainingSample) =
        if (items.any { it.kind == sample.kind && it.label == sample.label && it.question == sample.question }) null else sample.copy(id = items.size + 1L).also { items += it }
    override suspend fun list(status: SampleStatus?, limit: Int) = items.filter { status == null || it.status == status }
    override suspend fun review(id: Long, status: SampleStatus, label: String?) = true
    override suspend fun counts() = emptyMap<String, Int>()
    override suspend fun accepted(kind: SampleKind, users: Set<UserId>) = items.filter { it.kind == kind && it.status == SampleStatus.ACCEPTED }
    override suspend fun cursor() = at
    override suspend fun setCursor(messageId: Long) { at = messageId }
}

class SignalDetectorTest : FunSpec({
    test("담당을 지정해 같은 질문을 다시 물으면 앞 질문의 라우팅 재료 (명시 지정이었으면 아님, 10분 넘으면 아님)") {
        val found = SignalDetector.detect(listOf(turn(1, "오늘 서울 날씨 어때?"), turn(2, "/web 오늘 서울 날씨 어때", agent = "WEB", stage = "EXPLICIT")), 0)
        found.single().let {
            it.kind shouldBe SampleKind.ROUTING
            it.signal shouldBe SampleSignal.REASK
            it.label shouldBe "WEB"
            it.question shouldBe "오늘 서울 날씨 어때?"
            it.messageId shouldBe 1
            it.status shouldBe SampleStatus.ACCEPTED // 거의 같은 질문이라 확실함 0.9 이상
        }
        SignalDetector.detect(listOf(turn(1, "오늘 서울 날씨 어때?", stage = "EXPLICIT"), turn(2, "/web 오늘 서울 날씨 어때", agent = "WEB")), 0).shouldBeEmpty()
        SignalDetector.detect(listOf(turn(1, "오늘 서울 날씨 어때?"), turn(2, "/web 오늘 서울 날씨 어때", minutes = 30)), 0).shouldBeEmpty()
        // 이미 그 담당이 답했으면 아님, 다른 질문이면 아님
        SignalDetector.detect(listOf(turn(1, "오늘 서울 날씨 어때?", agent = "WEB"), turn(2, "/web 오늘 서울 날씨 어때")), 0).shouldBeEmpty()
        SignalDetector.detect(listOf(turn(1, "회의록 요약해 줘"), turn(2, "/web 환율 알려 줘")), 0).shouldBeEmpty()
    }

    test("말을 바꿔 다시 물음·바로잡음은 앞 답이 나빴다 (검토 대기), 승인 거부는 도구 재료. 커서 앞의 질문이 만든 신호는 뺀다") {
        val found = SignalDetector.detect(listOf(
            turn(1, "파이썬 리스트 정렬 방법"), turn(2, "파이썬에서 리스트 정렬하는 방법"),
            turn(3, "아니 그게 아니라 내림차순으로", denied = listOf("run_shell")),
        ), 0)
        found.map { it.signal to it.messageId } shouldBe listOf(SampleSignal.REPHRASE to 100L, SampleSignal.DENIAL to 3L, SampleSignal.CORRECTION to 200L)
        found.filter { it.kind == SampleKind.ANSWER }.all { it.label == "bad" && it.status == SampleStatus.PENDING } shouldBe true
        found.single { it.kind == SampleKind.TOOL }.label shouldBe "denied:run_shell"
        SignalDetector.detect(listOf(turn(1, "파이썬 리스트 정렬 방법"), turn(2, "파이썬에서 리스트 정렬하는 방법")), after = 2).shouldBeEmpty()
    }

    test("개인정보는 지운다") {
        val found = SignalDetector.detect(listOf(turn(1, "user@example.com 으로 메일 보내 줘"), turn(2, "/task user@example.com 으로 메일 보내 줘", agent = "TASK")), 0)
        found.single().question shouldContain "[제거됨]"
    }
})

class CuratorTest : FunSpec({
    test("쉬는 때만 돈다. 중간에 바빠지면 멈추고 커서를 옮기지 않으며, 다음에 이어서 중복 없이 넣는다") {
        val turns = listOf(turn(1, "오늘 서울 날씨 어때?"), turn(2, "/web 오늘 서울 날씨 어때", agent = "WEB"), turn(3, "회의록 정리"), turn(4, "아니 그게 아니라 요약만"))
        val source = object : CuratorSource { override suspend fun turns(afterMessageId: Long, users: Set<UserId>, limit: Int) = turns }
        val samples = MemorySamples()
        var idle = false
        val reports = mutableListOf<CuratorReport>()
        val curator = Curator(source, samples, { setOf(me) }, { idle }, record = { reports += it })
        curator.runOnce() shouldBe null
        var calls = 0
        idle = true
        val busyAfterOne = Curator(source, samples, { setOf(me) }, { calls++ < 2 }, record = { reports += it })
        busyAfterOne.runOnce()!!.stoppedEarly shouldBe true
        samples.at shouldBe 0
        samples.items.size shouldBe 1
        curator.runOnce()!!.let { it.stoppedEarly shouldBe false; it.added.size shouldBe 1 }
        samples.at shouldBe 4
        samples.items.map { it.signal } shouldBe listOf(SampleSignal.REASK, SampleSignal.CORRECTION)
        reports.size shouldBe 2
        // 동의한 사용자가 없으면 보지 않는다
        Curator(source, MemorySamples(), { emptySet() }, { true }).runOnce() shouldBe null
    }
})
