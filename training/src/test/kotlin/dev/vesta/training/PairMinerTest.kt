package dev.vesta.training

import dev.vesta.core.UserId
import dev.vesta.core.training.Passage
import dev.vesta.core.training.PassageSource
import dev.vesta.core.training.SampleKind
import dev.vesta.core.training.SampleSignal
import dev.vesta.core.training.SampleStatus
import dev.vesta.core.training.TrainingSample
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

private val owner = UserId(1)

private fun passage(id: Long, text: String, path: String = "docs/rules.md") =
    Passage(id, owner, 1, "bge-m3:567m", path, "휴가", text, "$path > 휴가\n$text", code = false)

private class FakePassages(val all: List<Passage>, val near: Map<String, List<Long>> = emptyMap()) : PassageSource {
    override suspend fun sample(users: Set<UserId>, exclude: Set<Long>, limit: Int) = all.filter { it.chunkId !in exclude }.take(limit)
    override suspend fun nearest(of: Passage, question: String, limit: Int) = near[question].orEmpty().mapNotNull { id -> all.firstOrNull { it.chunkId == id } }
    override suspend fun byIds(ids: List<Long>) = ids.mapNotNull { id -> all.firstOrNull { it.chunkId == id } }
    override suspend fun corpus(users: Set<UserId>, model: String) = all
}

class PairMinerTest : FunSpec({
    test("D6로 확인하고, 답하지 못하는 가까운 다른 파일 조각은 어려운 오답으로, 같이 답하는 조각은 오답에서 뺀다") {
        val all = listOf(
            passage(1, "연차는 1년 이상 근무하면 15일이 생긴다."),
            passage(2, "출장비는 실비로 정산한다."),
            passage(3, "육아휴직은 최대 1년 6개월이다."),
            passage(4, "연차 15일은 입사 1년 뒤부터다.", path = "docs/faq.md"),
            passage(5, "점심 시간은 12시부터 1시까지다.", path = "docs/office.md"),
            passage(6, "연차 신청은 전날까지 한다."),
        )
        val questions = mapOf(1L to "1. 연차는 며칠 생겨?", 2L to "\"출장비 정산 방식은?\"", 3L to "회사 주차 요금은?", 4L to "연차 언제부터 생겨?", 5L to "", 6L to "연차 신청 기한은 언제야?")
        val answers = mapOf("연차는 며칠 생겨?" to setOf(1L), "출장비 정산 방식은?" to setOf(2L), "연차 언제부터 생겨?" to setOf(1L, 4L))
        val source = FakePassages(all, near = mapOf("연차는 며칠 생겨?" to listOf(6, 4, 1, 5), "출장비 정산 방식은?" to listOf(5, 2), "연차 언제부터 생겨?" to listOf(1, 4)))
        val samples = MemorySamples()
        val miner = PairMiner(source, samples, ask = { questions[it.chunkId] }) { q, text -> if (all.any { p -> p.passage == text && p.chunkId in answers[q].orEmpty() }) 0.95 else 0.1 }
        val added = miner.run(setOf(owner), 10)
        added.size shouldBe 6
        added.first { it.messageId == 1L }.let {
            it.status shouldBe SampleStatus.ACCEPTED
            it.question shouldBe "연차는 며칠 생겨?"
        }
        // 6은 같은 파일이라 보지 않고, 4(faq.md)·5(office.md)는 답하지 못해(0.1) 오답
        added.first { it.messageId == 1L }.label shouldBe "4,5"
        added.first { it.messageId == 2L }.let { it.question shouldBe "출장비 정산 방식은?"; it.label shouldBe "5"; it.note!!.contains("순위 2") shouldBe true }
        added.first { it.messageId == 3L }.status shouldBe SampleStatus.REJECTED // 지어낸 질문 (D6 낮음)
        added.first { it.messageId == 4L }.let { it.status shouldBe SampleStatus.ACCEPTED; it.label shouldBe "-"; it.note!!.contains("다른 조각도 답함: docs/rules.md") shouldBe true }
        added.first { it.messageId == 5L }.status shouldBe SampleStatus.REJECTED // 질문 없음
        added.all { it.kind == SampleKind.RETRIEVAL && it.signal == SampleSignal.SYNTHETIC } shouldBe true
        // 다음에는 이미 본 조각을 다시 묻지 않는다
        miner.run(setOf(owner), 10).size shouldBe 0
    }

    test("바빠지면 멈춘다, 동의한 사용자가 없으면 아무것도 하지 않는다") {
        val source = FakePassages(listOf(passage(1, "가".repeat(200)), passage(2, "나".repeat(200))))
        var asked = 0
        val miner = PairMiner(source, MemorySamples(), ask = { asked++; null }) { _, _ -> 0.0 }
        miner.run(setOf(owner), 10) { asked >= 1 }.size shouldBe 1
        miner.run(emptySet(), 10).size shouldBe 0
    }

    test("질문 다듬기: 첫 줄, 번호·따옴표·머리말 떼기, 너무 짧으면 버림") {
        PairMiner.clean("질문: 연차는 며칠이야?\n설명…") shouldBe "연차는 며칠이야?"
        PairMiner.clean("2) “10분 넘게 걸리면 어떻게 해?”") shouldBe "10분 넘게 걸리면 어떻게 해?"
        PairMiner.clean("  \n뭐?") shouldBe null
    }
})

class EmbeddingDataTest : FunSpec({
    test("검토를 거친 재료 → 학습·떼어 두기·평가 파일. 사라진 조각은 빼고, 정답 위치가 없는 평가 질문은 뺀다") {
        val all = (1L..20L).map { passage(it, "조각 $it 내용", path = if (it == 7L) "web/UrlGuard.kt" else "docs/rules.md") }
        val samples = (1L..20L).map {
            TrainingSample(it, owner, SampleKind.RETRIEVAL, SampleSignal.SYNTHETIC, it, "질문 $it", null, if (it == 3L) "4,99" else "-", 0.95, SampleStatus.ACCEPTED)
        } + TrainingSample(21, owner, SampleKind.RETRIEVAL, SampleSignal.SYNTHETIC, 404, "사라진 조각", null, "-", 0.95, SampleStatus.ACCEPTED)
        val dir = Files.createTempDirectory("embed")
        val eval = Files.createTempFile("eval", ".tsv").also { Files.writeString(it, "SSRF 막는 곳\tUrlGuard.kt\n없는 파일\tNope.kt\n") }
        val files = EmbeddingData.write(dir, samples, FakePassages(all), setOf(owner), "bge-m3:567m", listOf(eval, dir.resolve("없음.tsv")))
        val held = (1L..20L).count { EmbeddingData.heldOut(it) }
        files.held shouldBe held
        files.train shouldBe 20 - held
        val lines = Files.readAllLines(files.pairs).map { Json.parseToJsonElement(it).jsonObject }
        if (!EmbeddingData.heldOut(3)) lines.first { it["query"]!!.jsonPrimitive.content == "질문 3" }["negatives"]!!.jsonArray.size shouldBe 1
        Files.readAllLines(files.corpus).size shouldBe 20
        Files.readAllLines(files.eval).single().contains("SSRF") shouldBe true
    }
})
