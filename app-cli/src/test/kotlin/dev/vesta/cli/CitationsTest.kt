package dev.vesta.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CitationsTest : FunSpec({
    test("[n], [n][m], [n, m] 형식을 모두 찾는다") {
        Citations.numbers("A다 [1]. B다 [2][5]. C다 [3, 4]. 배열 a[i]는 무시") shouldBe setOf(1, 2, 3, 4, 5)
        Citations.numbers("인용 없음") shouldBe emptySet()
    }
})
