package dev.vesta.retrieval

import dev.vesta.core.SourceType
import dev.vesta.core.knowledge.Evidence
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RetrievalTest : FunSpec({
    test("검색어에서 조사와 불용어를 뗀다") {
        val terms = KeywordQuery.terms("회의록은 어디에 저장하는지 알려줘. MLX는?")
        terms shouldContainAll listOf("회의록", "mlx")
        terms shouldNotContain "알려줘"
        KeywordQuery.match("메모리 예산은?") shouldBe "\"메모리\"* OR \"예산\"*"
    }

    test("조사를 떼면 한 글자가 되는 말은 그대로 둔다") {
        KeywordQuery.terms("모델은") shouldBe listOf("모델")
        KeywordQuery.terms("이가") shouldBe listOf("이가")
    }

    test("RRF는 여러 목록에서 위에 있는 것을 올린다") {
        val fused = Rrf.fuse(listOf(listOf(1L, 2L, 3L), listOf(3L, 1L, 4L)))
        fused.map { it.first }.take(2) shouldBe listOf(1L, 3L)
    }

    test("컨텍스트는 번호를 붙이고 예산을 넘는 근거는 뺀다") {
        val evidence = (1..3).map { Evidence(SourceType.LOCAL_DOCS, "/doc$it.md", "문서$it", "${it}행", "내용$it ".repeat(20), 1.0) }
        val context = ContextAssembler.assemble(evidence, budgetChars = 250)
        context.evidence.map { it.citation } shouldBe listOf(1, 2)
        context.text shouldContain "[2] 문서2 (2행)"
    }
})
