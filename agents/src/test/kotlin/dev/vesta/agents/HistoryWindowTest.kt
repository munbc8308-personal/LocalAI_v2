package dev.vesta.agents

import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class HistoryWindowTest : FunSpec({
    test("아주 긴 지난 답 하나가 앞 맥락을 밀어내지 않는다, 지금 질문은 자르지 않는다") {
        val history = listOf(
            ChatMessage(ChatRole.USER, "2단계를 해 줘"),
            ChatMessage(ChatRole.ASSISTANT, "x".repeat(170_000)),
            ChatMessage(ChatRole.USER, "이어서 해 줘 " + "y".repeat(20_000)),
        )
        val fit = HistoryWindow.fit(history, 60_000)
        fit.map { it.role } shouldBe listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER)
        fit[0].content shouldBe "2단계를 해 줘"
        fit[1].content shouldContain "자 생략"
        (fit[1].content.length < 9_000) shouldBe true
        fit[2].content.length shouldBe 20_008
    }

    test("지난 답의 같은 줄 되풀이는 접는다 (지금 질문은 그대로)") {
        val loop = List(30) { "파일을 생성할게." }.joinToString("\n\n")
        val fit = HistoryWindow.fit(listOf(ChatMessage(ChatRole.USER, "만들어"), ChatMessage(ChatRole.ASSISTANT, "시작\n$loop"), ChatMessage(ChatRole.USER, "이어서")), 60_000)
        fit[1].content.lines().count { it == "파일을 생성할게." } shouldBe 2
        fit[1].content shouldContain "30번"
        HistoryWindow.collapse("a\nb\na") shouldBe "a\nb\na"
    }
})
