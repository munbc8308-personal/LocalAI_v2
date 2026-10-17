package dev.vesta.knowledge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class CodeChunkerTest : FunSpec({
    val kotlin = buildString {
        appendLine("package a")
        appendLine()
        appendLine("import b.C")
        appendLine()
        appendLine("/** 문서 */")
        appendLine("class Router(private val x: Int) {")
        repeat(40) { appendLine("    fun step$it() = println(\"${"x".repeat(30)}\")") }
        appendLine("}")
        appendLine()
        appendLine("private fun helper(): Int = 1")
        appendLine()
        appendLine("data class Result(val ok: Boolean)")
    }

    test("선언 단위로 자르고 위치는 상대경로:줄, 이름을 단다") {
        val chunks = CodeChunker(targetChars = 800, maxLines = 30).chunk("src/Router.kt", kotlin)
        chunks.first().location!! shouldStartWith "src/Router.kt:1–"
        chunks.mapNotNull { it.heading } shouldContain "Router"
        chunks.joinToString("\n") { it.text } shouldContain "data class Result"
        chunks.forEach { c ->
            val (s, e) = c.location!!.substringAfter(':').split('–').map { it.toInt() }
            (e - s < 30) shouldBe true
        }
    }

    test("작은 선언은 합친다") {
        val chunks = CodeChunker().chunk("x.py", "def a():\n    return 1\n\ndef b():\n    return 2\n")
        chunks.size shouldBe 1
        chunks[0].location shouldBe "x.py:1–6"
    }

    test("지원 확장자") {
        CodeFiles.supports(java.nio.file.Path.of("a/B.kt")) shouldBe true
        CodeFiles.supports(java.nio.file.Path.of("a/b.png")) shouldBe false
    }
})
