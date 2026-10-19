package dev.vesta.channels

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

class MessageSplitterTest : FunSpec({
    test("짧으면 그대로") {
        MessageSplitter.split("안녕\n\n반가워", 4096) shouldBe listOf("안녕\n\n반가워")
    }

    test("문단 경계에서 자르고 모든 조각이 한도 안") {
        val paragraph = "가".repeat(300)
        val text = List(10) { paragraph }.joinToString("\n\n")
        val parts = MessageSplitter.split(text, 1000)
        parts.forEach { (it.length <= 1000) shouldBe true }
        parts.joinToString("\n\n") shouldBe text
    }

    test("코드 블록 중간에서 자르면 닫고 같은 언어로 다시 연다") {
        val code = (1..200).joinToString("\n") { "val x$it = $it" }
        val text = "설명\n```kotlin\n$code\n```\n끝"
        val parts = MessageSplitter.split(text, 800)
        (parts.size > 1) shouldBe true
        parts.forEach { (it.length <= 800) shouldBe true }
        parts.first() shouldEndWith "```"
        parts[1] shouldStartWith "```kotlin\n"
        parts.forEach { part -> (part.lines().count { it.trim().startsWith("```") } % 2) shouldBe 0 }
        parts.last() shouldEndWith "끝"
    }

    test("한 줄이 한도보다 길면 줄을 자른다") {
        val parts = MessageSplitter.split("a".repeat(2500), 1000)
        parts shouldHaveSize 3
        parts.joinToString("") shouldBe "a".repeat(2500)
    }

    test("연결 코드 해시는 대소문자·하이픈을 무시") {
        PairingCode.hash("abcd-efgh") shouldBe PairingCode.hash("ABCDEFGH")
        PairingCode.generate().length shouldBe 8
    }
})
