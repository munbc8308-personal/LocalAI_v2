package dev.vesta.knowledge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ParsingChunkingTest : FunSpec({
    val markdown = """
        # Vesta 기획서

        ## 2. 사용 환경

        ### 2.2 메모리 예산
        총 128GB 중 70%를 쓴다.

        ```sh
        # 이건 제목이 아니다
        sysctl iogpu.wired_limit_mb
        ```

        ## 3. 추론 엔진
        MLX를 쓴다.
    """.trimIndent()

    test("Markdown: 제목 경로와 줄 번호, 코드 블록 안 #은 제목이 아니다") {
        val doc = MarkdownParser.parse(markdown, "fallback")
        doc.title shouldBe "Vesta 기획서"
        doc.blocks.map { it.heading } shouldBe listOf(
            "Vesta 기획서 > 2. 사용 환경 > 2.2 메모리 예산",
            "Vesta 기획서 > 2. 사용 환경 > 2.2 메모리 예산",
            "Vesta 기획서 > 3. 추론 엔진",
        )
        doc.blocks[0].startLine shouldBe 6
        doc.blocks[1].text shouldContain "# 이건 제목이 아니다"
    }

    test("청크는 구역이 바뀌면 나뉘고 위치를 줄 번호로 단다") {
        val chunks = Chunker().chunk(MarkdownParser.parse(markdown, "x"))
        chunks shouldHaveSize 2
        chunks[0].heading shouldBe "Vesta 기획서 > 2. 사용 환경 > 2.2 메모리 예산"
        chunks[0].location shouldBe "6–11행"
        chunks[1].text shouldNotContain "128GB"
    }

    test("긴 구역은 목표 크기로 나누고 짧은 마지막 문단을 겹친다") {
        val paragraphs = (1..6).joinToString("\n\n") { "문단$it " + "가".repeat(if (it % 2 == 0) 50 else 400) }
        val chunks = Chunker(targetChars = 600, overlapChars = 100).chunk(PlainTextParser.parse(paragraphs, "t"))
        (chunks.size >= 3) shouldBe true
        chunks.zipWithNext().forEach { (a, b) ->
            val lastOfA = a.text.substringAfterLast("\n\n")
            if (lastOfA.length <= 100) b.text.startsWith(lastOfA) shouldBe true
        }
        chunks.joinToString { it.text } shouldContain "문단6"
    }

    test("목표보다 긴 문단은 잘라서 넣는다") {
        val chunks = Chunker(targetChars = 100, overlapChars = 0).chunk(PlainTextParser.parse("가".repeat(350), "t"))
        chunks.forEach { (it.text.length <= 100) shouldBe true }
        chunks.sumOf { it.text.length } shouldBe 350
    }
})
