package dev.vesta.decision

import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.DecisionQuestion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class JudgeTest : FunSpec({
    test("답변을 검증할 주장 문장으로 나눈다 (코드·표·제목·짧은 문장·출처 번호 제외)") {
        val answer = """
            ## 요약
            메모리 예산은 89GB로 정해져 있습니다 [1]. 메인 모델은 27.3GB를 씁니다 [2].
            - **결정 모델**은 winnow:e4b로 교체되었습니다 [1, 3].
            | 항목 | 값 |
            ```kotlin
            val x = 1 // 이건 코드라서 제외되는 아주 긴 문장입니다
            ```
            네.
            더 궁금한 점이 있으신가요?
        """.trimIndent()
        Sentences.claims(answer) shouldContainExactly listOf(
            "메모리 예산은 89GB로 정해져 있습니다.",
            "메인 모델은 27.3GB를 씁니다.",
            "결정 모델은 winnow:e4b로 교체되었습니다.",
        )
    }

    test("판정 실패 시 기능을 막지 않는 쪽 값") {
        val broken = object : DecisionClient {
            override suspend fun decide(state: String, questions: Map<String, DecisionQuestion>): Map<String, DecisionAnswer> = error("down")
        }
        val judge = Judge(broken)
        judge.sufficient("q", "e") shouldBe 1.0
        judge.injection("t") shouldBe 0.0
        judge.grounded("e", listOf("a", "b")) shouldBe listOf(1.0, 1.0)
    }

    test("문장 검증은 묶어서 묻고 순서대로 돌려준다") {
        val fake = object : DecisionClient {
            override suspend fun decide(state: String, questions: Map<String, DecisionQuestion>) =
                questions.mapValues { (_, q) -> DecisionAnswer.Noul(if ("근거있음" in q.instructions) 0.9 else 0.1) }
        }
        Judge(fake).grounded("e", listOf("근거있음 A", "없음 B", "근거있음 C")) shouldBe listOf(0.9, 0.1, 0.9)
    }
})
