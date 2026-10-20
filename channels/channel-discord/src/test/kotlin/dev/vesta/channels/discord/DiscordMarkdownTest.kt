package dev.vesta.channels.discord

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DiscordMarkdownTest : FunSpec({
    test("코드 블록 안의 표·제목은 그대로, 밖의 깊은 제목은 굵게") {
        DiscordMarkdown.render("#### 작은 제목\n```\n| a | b |\n|---|---|\n```\n\n\n\n끝") shouldBe
            "**작은 제목**\n```\n| a | b |\n|---|---|\n```\n\n끝"
    }

    test("이스케이프") {
        DiscordMarkdown.escape("my_file*.md") shouldBe "my\\_file\\*.md"
        DiscordMarkdown.codeBlock("a ``` b") shouldBe "```\na `​`` b\n```"
    }
})
