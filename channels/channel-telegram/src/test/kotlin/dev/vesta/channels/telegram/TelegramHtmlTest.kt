package dev.vesta.channels.telegram

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class TelegramHtmlTest : FunSpec({
    test("서식과 이스케이프") {
        TelegramHtml.render("# 제목\n- **굵게** `a<b>` *기울임*\n1. [링크](https://x.com/?a=1&b=\"2\")") shouldBe
            "<b>제목</b>\n• <b>굵게</b> <code>a&lt;b&gt;</code> <i>기울임</i>\n1. <a href=\"https://x.com/?a=1&amp;b=&quot;2&quot;\">링크</a>"
    }

    test("코드 블록은 언어를 붙이고 안쪽은 서식을 적용하지 않는다") {
        TelegramHtml.render("```kotlin\nval s = \"**x**\" < 3\n```") shouldBe
            "<pre><code class=\"language-kotlin\">val s = \"**x**\" &lt; 3</code></pre>"
        // 이상한 언어 이름은 버린다
        TelegramHtml.render("```a\"><script>\nx\n```") shouldBe "<pre><code>x</code></pre>"
    }

    test("javascript 링크와 HTML 태그는 글자로") {
        val html = TelegramHtml.render("[x](javascript:alert(1)) <img src=x onerror=alert(1)>")
        html shouldNotContain "<a"
        html shouldNotContain "<img"
        html shouldContain "&lt;img"
    }

    test("표는 고정폭 블록") {
        val html = TelegramHtml.render("| 이름 | 값 |\n|---|---|\n| a | 1 |")
        html shouldBe "<pre>이름 | 값\n-----+---\na    | 1</pre>"
    }
})
