package dev.vesta.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

class PasswordHasherTest : FunSpec({
    test("같은 비밀번호만 통과하고, 매번 다른 salt를 쓴다") {
        val a = PasswordHasher.hash("한글 비밀번호 1234")
        a shouldStartWith "\$argon2id\$v=19\$m=65536,t=3,p=1\$"
        PasswordHasher.verify("한글 비밀번호 1234", a) shouldBe true
        PasswordHasher.verify("한글 비밀번호 1235", a) shouldBe false
        PasswordHasher.hash("한글 비밀번호 1234") shouldNotBe a
    }

    test("형식이 틀린 저장값은 거부한다") {
        PasswordHasher.verify("x", "plain") shouldBe false
        PasswordHasher.verify("x", "\$argon2i\$v=19\$m=8,t=1,p=1\$AAAA\$AAAA") shouldBe false
    }
})
