package dev.vesta.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DoctorTest : FunSpec({
    test("한글은 두 칸으로 세어 줄을 맞춘다") {
        pad("Ollama", 10) shouldBe "Ollama    "
        pad("메모리", 10) shouldBe "메모리    "
        pad("dev.vesta.server", 10) shouldBe "dev.vesta.server "
    }
})
