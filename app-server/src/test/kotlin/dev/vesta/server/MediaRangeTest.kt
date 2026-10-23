package dev.vesta.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MediaRangeTest : FunSpec({
    test("영상 범위 요청: 처음부터·끝에서부터·끝 없음, 틀리면 전체(null)") {
        MediaRange.parse("bytes=0-1023", 4_000) shouldBe 0..1023
        MediaRange.parse("bytes=3000-", 4_000) shouldBe 3000..3999
        MediaRange.parse("bytes=-500", 4_000) shouldBe 3500..3999
        MediaRange.parse("bytes=0-99999", 4_000) shouldBe 0..3999
        MediaRange.parse("bytes=5000-", 4_000) shouldBe null
        MediaRange.parse("bytes=10-5", 4_000) shouldBe null
        MediaRange.parse("bytes=0-1,5-9", 4_000) shouldBe null
        MediaRange.parse("items=0-1", 4_000) shouldBe null
        MediaRange.parse(null, 4_000) shouldBe null
    }
})
