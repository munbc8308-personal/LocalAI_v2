package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

class ExposedChannelBindingStoreTest : FunSpec({
    test("연결 코드는 한 번만, 만료 전까지만 쓸 수 있고 연결은 세션을 기억한다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        var now = Instant.parse("2026-10-19T00:00:00Z")
        val store = ExposedChannelBindingStore(db.db, object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        })
        val tg = ChannelType.TELEGRAM

        store.createPairing(owner, tg, "h1", Duration.ofMinutes(10))
        store.createPairing(owner, tg, "h2", Duration.ofMinutes(10))
        store.consumePairing(tg, "h1") shouldBe null // 새 코드를 만들면 이전 코드는 무효
        store.consumePairing(ChannelType.DISCORD, "h2") shouldBe null
        store.consumePairing(tg, "h2")?.role shouldBe UserRole.OWNER
        store.consumePairing(tg, "h2") shouldBe null

        store.createPairing(owner, tg, "h3", Duration.ofMinutes(10))
        now = now.plus(Duration.ofMinutes(11))
        store.consumePairing(tg, "h3") shouldBe null

        store.find(tg, "42") shouldBe null
        store.bind(owner, tg, "42", "42")
        store.find(tg, "42")?.user?.userId shouldBe owner
        store.setSession(tg, "42", SessionId("s1"))
        store.find(tg, "42")?.sessionId shouldBe SessionId("s1")
        store.bind(owner, tg, "42", "42") // 다시 연결하면 새 대화
        store.find(tg, "42")?.sessionId shouldBe null
        store.list(tg).size shouldBe 1
        store.remove(tg, "42") shouldBe true
        store.find(tg, "42") shouldBe null
    }
})
