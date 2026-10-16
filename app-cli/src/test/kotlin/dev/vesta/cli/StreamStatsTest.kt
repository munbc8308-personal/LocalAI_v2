package dev.vesta.cli

import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.Usage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf

class StreamStatsTest : FunSpec({
    test("엔진 사용량이 있으면 그 토큰 수를 쓴다") {
        val stats = StreamStats.collect(
            flowOf(ChatChunk.Delta("가"), ChatChunk.Delta("나"), ChatChunk.Done("stop", Usage(10, 7))),
        )
        stats.text shouldBe "가나"
        stats.completionTokens shouldBe 7
        stats.promptTokens shouldBe 10
    }

    test("사용량이 없으면 조각 수로 근사") {
        val stats = StreamStats.collect(flowOf(ChatChunk.Delta("a"), ChatChunk.Delta("b"), ChatChunk.Done(null, null)))
        stats.completionTokens shouldBe 2
        stats.promptTokens shouldBe null
    }

    test("중앙값") {
        listOf(5L, 1L, 3L).median() shouldBe 3L
        emptyList<Long>().median() shouldBe 0L
    }
})
