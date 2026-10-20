package dev.vesta.channels.slack

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val Z = "​"

class SlackMrkdwnTest : FunSpec({
    test("서식 변환") {
        SlackMrkdwn.render("## 제목\n- **굵게** 와 *기울임*\n> 인용 <b>\n\n\n[링크](https://a.com/x|y) `a<b`") shouldBe
            "$Z*제목*$Z\n• $Z*굵게*$Z 와 ${Z}_기울임_$Z\n> 인용 &lt;b&gt;\n\n<https://a.com/x%7Cy|링크> $Z`a&lt;b`$Z"
    }

    test("코드 블록은 언어를 버리고 이스케이프, 표는 고정폭") {
        SlackMrkdwn.render("```kotlin\nval a = b < c\n```") shouldBe "```\nval a = b &lt; c\n```"
        SlackMrkdwn.render("| a | b |\n|---|---|\n| 가 | 1 |") shouldBe "```\na  | b\n---+--\n가 | 1\n```"
    }

    test("받은 글 되돌리기") {
        SlackMrkdwn.unescape("<https://a.com|a.com> a &lt; b &amp;&amp; c") shouldBe "https://a.com a < b && c"
    }
})
