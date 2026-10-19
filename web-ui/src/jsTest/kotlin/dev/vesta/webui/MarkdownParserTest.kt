package dev.vesta.webui

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownParserTest {
    @Test
    fun blocks() {
        val blocks = MarkdownParser.blocks(
            """
            ## 제목
            첫 줄
            둘째 줄

            - 하나
            - 둘

            | a | b |
            |---|---|
            | 1 | 2 |

            ```kotlin
            val x = 1
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MdBlock.Heading(2, "제목"),
                MdBlock.Paragraph(listOf("첫 줄", "둘째 줄")),
                MdBlock.ListBlock(false, listOf("하나", "둘")),
                MdBlock.TableBlock(listOf("a", "b"), listOf(listOf("1", "2"))),
                // 스트리밍 중 닫히지 않은 코드 블록
                MdBlock.Code("kotlin", "val x = 1"),
            ),
            blocks,
        )
    }

    @Test
    fun inline() {
        assertEquals(
            listOf(
                MdSpan.Bold("굵게"), MdSpan.Plain(" "), MdSpan.CodeSpan("code"), MdSpan.Plain(" 근거"),
                MdSpan.Citation(1), MdSpan.Citation(2), MdSpan.Plain(" "), MdSpan.CodeSpan("O(n)"), MdSpan.Plain(" "),
                MdSpan.Link("링크", "https://example.com"), MdSpan.Plain(" 가격 $5"),
            ),
            MarkdownParser.inline("**굵게** `code` 근거[1, 2] \$O(n)\$ [링크](https://example.com) 가격 \$5"),
        )
    }

    @Test
    fun unsafeLinksStayText() {
        assertEquals(listOf(MdSpan.Plain("[x](javascript:alert(1))")), MarkdownParser.inline("[x](javascript:alert(1))"))
    }
}
