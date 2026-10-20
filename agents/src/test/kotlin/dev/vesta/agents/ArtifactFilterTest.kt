package dev.vesta.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ArtifactFilterTest : FunSpec({
    fun run(vararg pieces: String) = ArtifactFilter().let { f -> pieces.joinToString("") { f.accept(it) } + f.flush() }

    test("앞의 thought 줄과 채널 표식을 지운다") {
        run("thought", "\n", "회의록 내용을", " 요약합니다") shouldBe "회의록 내용을 요약합니다"
        run(":thought\n사용자가", " 바꿨습니다") shouldBe "사용자가 바꿨습니다"
        run("<channel|>") shouldBe ""
        run("완료", "<channel|>", "했습니다") shouldBe "완료했습니다"
    }

    test("평범한 답은 그대로") {
        run("tho", "ugh") shouldBe "though"
        run("안녕하세요") shouldBe "안녕하세요"
        run("thought experiment") shouldBe "thought experiment"
        run(":", ") 웃음") shouldBe ":) 웃음"
    }
})
