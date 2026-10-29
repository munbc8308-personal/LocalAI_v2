package dev.vesta.core.identity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AvatarTest : FunSpec({
    test("사무실 캐릭터 꾸미기는 정해 둔 값만 받는다 (M38)") {
        Avatar.parse("top=hoodie,#7d4cdb;bottom=#263238;hair=long,#4a2f1d;acc=glasses") shouldNotBe null
        Avatar.parse("top=hoodie,red") shouldBe null
        Avatar.parse("top=<script>,#000000") shouldBe null
        Avatar.parse("acc=crown;evil=1") shouldBe null
        Avatar.parse("x".repeat(201)) shouldBe null
    }
})
