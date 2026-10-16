package dev.vesta.storage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class VestaDatabaseTest : FunSpec({
    test("마이그레이션은 여러 번 해도 안전하고 OWNER는 하나만 생긴다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("sub/vesta.db"))
        db.migrate()
        db.migrate()
        db.schemaVersion() shouldBe VestaDatabase.CURRENT_VERSION

        val first = db.ensureOwner("owner")
        val second = db.ensureOwner("someone-else")
        second shouldBe first
    }
})
