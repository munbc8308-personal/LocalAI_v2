package dev.vesta.webui

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextTest {
    @Test
    fun 문장이_끝난_곳까지만_자르고_짧으면_붙인다() {
        val text = "네. 내일 서울은 맑겠습니다. 최고 기온은"
        val first = "네. 내일 서울은 맑겠습니다. ".length
        // "네."는 8자 미만이라 다음 문장과 함께
        assertEquals(first, SpeechText.cut(text, 0, finished = false))
        assertEquals(first, SpeechText.cut(text, first, finished = false))
        assertEquals(text.length, SpeechText.cut(text, first, finished = true))
    }

    @Test
    fun 닫히지_않은_코드_블록_앞까지만_읽는다() {
        val intro = "예시 코드는 이렇습니다.\n"
        val open = intro + "```kotlin\nval a = 1. 더 긴 문장입니다."
        assertEquals(intro.length, SpeechText.cut(open, 0, finished = false))
        assertEquals(intro.length, SpeechText.cut(open, intro.length, finished = false))
    }

    @Test
    fun 코드_출처_링크_기호는_읽지_않는다() {
        assertEquals(
            "굵게 설명 코드는 화면에 있습니다. 자세히는 문서 참고",
            SpeechText.clean("**굵게** 설명 [1]\n```kt\nfun x() {}\n```\n자세히는 [문서](https://a.b) 참고"),
        )
        assertEquals("주소는 링크 입니다", SpeechText.clean("주소는 https://example.com 입니다"))
    }
}
