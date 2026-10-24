package dev.vesta.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class HardwareProfileTest : FunSpec({
    val gib = 1024L * 1024 * 1024

    test("메모리에 맞는 메인 모델 (Mac 메모리는 GiB)") {
        HardwareProfile.recommend(128 * gib).let { it.general shouldBe "gemma4:26b-mxfp8"; it.clips shouldBe true }
        HardwareProfile.recommend(96 * gib).general shouldBe "gemma4:26b-mxfp8"
        HardwareProfile.recommend(64 * gib).let { it.general shouldBe "gemma4:26b-nvfp4"; it.clips shouldBe false }
        HardwareProfile.recommend(48 * gib).general shouldBe "gemma4:12b-mxfp8"
        HardwareProfile.recommend(36 * gib).general shouldBe "gemma4:12b-nvfp4"
        HardwareProfile.recommend(32 * gib).general shouldBe "gemma4:12b-nvfp4"
        HardwareProfile.recommend(24 * gib).general shouldBe "gemma4:e4b-mxfp8"
        HardwareProfile.recommend(16 * gib).general shouldBe "gemma4:e4b-mxfp8"
    }

    test("설정 틀의 메인·코드·비전 모델을 모두 바꾼다") {
        val template = ConfigTemplate.text()
        val applied = HardwareProfile.apply(template, HardwareProfile.recommend(32 * gib))
        applied.contains("gemma4:26b-mxfp8") shouldBe false
        applied shouldContain "general:   { name: \"gemma4:12b-nvfp4\""
        applied shouldContain "code:      { name: \"gemma4:12b-nvfp4\""
    }
})

private object ConfigTemplate {
    fun text() = dev.vesta.core.config.ConfigLoader.defaultTemplate()
}
