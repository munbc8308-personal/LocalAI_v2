package dev.vesta.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KoreanStyleTest : FunSpec({
    test("한자 단어를 찾는다") {
        KoreanStyle.hanWords("코루틴은 轻型 동시성 모델입니다") shouldBe listOf("轻型")
        KoreanStyle.hanWords("한글만 있습니다") shouldBe emptyList()
    }

    test("영문·숫자 뒤 조사 띄어쓰기를 센다") {
        KoreanStyle.spacedParticles("MLX 은 빠르고 GPU 와 CPU 를 공유한다.") shouldBe 3
        KoreanStyle.spacedParticles("MLX는 빠르고 GPU와 CPU를 공유한다.") shouldBe 0
        KoreanStyle.spacedParticles("API 이용 방법") shouldBe 0
    }
})
