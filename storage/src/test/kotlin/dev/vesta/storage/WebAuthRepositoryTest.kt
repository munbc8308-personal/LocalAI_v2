package dev.vesta.storage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

class WebAuthRepositoryTest : FunSpec({
    test("세션은 만료 전까지만 찾고, 비밀번호를 바꾸면 모두 지운다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        var now = Instant.parse("2026-10-19T00:00:00Z")
        val repo = WebAuthRepository(db.db, object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        })

        repo.credential(null)?.name shouldBe "owner"
        repo.credential(null)?.passwordHash shouldBe null
        repo.setPasswordHash(owner, "h1")
        repo.credential("owner")?.passwordHash shouldBe "h1"

        repo.createSession(owner, "t1", "c1", "phone", Duration.ofDays(1))
        repo.findSession("t1")?.csrf shouldBe "c1"
        repo.findSession("other") shouldBe null

        now = now.plus(Duration.ofDays(2))
        repo.findSession("t1") shouldBe null

        repo.createSession(owner, "t2", "c2", null, Duration.ofDays(1))
        repo.setPasswordHash(owner, "h2")
        repo.findSession("t2") shouldBe null
    }
})
