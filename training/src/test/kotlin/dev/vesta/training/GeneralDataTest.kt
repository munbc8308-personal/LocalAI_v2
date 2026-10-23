package dev.vesta.training

import dev.vesta.core.UserId
import dev.vesta.core.training.CuratorTurn
import dev.vesta.core.training.SampleKind
import dev.vesta.core.training.SampleSignal
import dev.vesta.core.training.SampleStatus
import dev.vesta.core.training.TrainingSample
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant

class GeneralDataTest : FunSpec({
    test("👍 받은 일반 대화 답만: 근거·도구를 본 답, 👎, 답 없음은 뺀다. 같은 질문은 하나") {
        fun sample(id: Long, label: String, q: String = "질문 $id", answer: String? = "답 $id") =
            TrainingSample(id, UserId(1), SampleKind.ANSWER, SampleSignal.FEEDBACK, id * 10, q, answer, label, 1.0, SampleStatus.ACCEPTED)
        val agents = mapOf(10L to "CHAT", 20L to "DOCS", 30L to "CHAT", 40L to "CHAT", 50L to "CHAT")
        val hooks = GeneralHooks(
            turn = { id -> agents[id]?.let { CuratorTurn(UserId(1), "s", id - 1, "q", Instant.parse("2026-10-23T01:00:00Z"), id, "a", it) } },
            system = { "시스템 $it" },
            current = { "gemma4:26b-mxfp8" },
        )
        val rows = GeneralData.rows(listOf(sample(1, "good"), sample(2, "good"), sample(3, "bad"), sample(4, "good", answer = null), sample(5, "good", q = "질문 1")), hooks)
        rows shouldBe listOf(GeneralData.Row("시스템 2026-10-23T01:00:00Z", "질문 1", "답 1"))
        val file = GeneralData.write(rows, Files.createTempFile("sft", ".jsonl"))
        Files.readAllLines(file).single().contains("\"answer\":\"답 1\"") shouldBe true
    }
})
