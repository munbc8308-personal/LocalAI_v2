package dev.vesta.identity

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.RiskLevel
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.core.session.NewMessage
import dev.vesta.storage.ExposedMessageRepository
import dev.vesta.storage.ExposedSessionRepository
import dev.vesta.storage.ExposedUserRepository
import dev.vesta.storage.KnowledgeRepository
import dev.vesta.storage.NewChunk
import dev.vesta.storage.VestaDatabase
import dev.vesta.storage.WebAuthRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.io.path.createTempDirectory

class IdentityTest : FunSpec({
    test("역할 표: MEMBER는 Apple 앱·셸 없음, GUEST는 대화·웹만, 관리는 OWNER만") {
        RolePolicy.agents(UserRole.OWNER) shouldBe AgentType.entries.toSet()
        (AgentType.PERSONAL in RolePolicy.agents(UserRole.MEMBER)) shouldBe true
        RolePolicy.agents(UserRole.GUEST) shouldBe setOf(AgentType.CHAT, AgentType.WEB)
        RolePolicy.allowsRisk(UserRole.MEMBER, RiskLevel.EXEC) shouldBe false
        RolePolicy.allowsRisk(UserRole.MEMBER, RiskLevel.WRITE) shouldBe true
        RolePolicy.allowsRisk(UserRole.GUEST, RiskLevel.READ) shouldBe false
        RolePolicy.allowsRisk(UserRole.OWNER, RiskLevel.EXEC) shouldBe true
        RolePolicy.canManage(UserRole.MEMBER) shouldBe false
    }

    test("요청 큐: 자리가 차면 기다리고, 사용자별로 돌아가며 꺼낸다") {
        val scheduler = FairScheduler(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val waits = Collections.synchronizedList(mutableListOf<Int>())
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            // A가 자리를 차지한 동안 A가 둘, B가 하나 더 보낸다 → A2보다 B1이 먼저
            launch { scheduler.run(UserId(1)) { order += "a0"; gate.await() } }
            delay(50)
            launch { scheduler.run(UserId(1), onWait = { waits += it }) { order += "a1" } }
            delay(20)
            launch { scheduler.run(UserId(1), onWait = { waits += it }) { order += "a2" } }
            delay(20)
            launch { scheduler.run(UserId(2), onWait = { waits += it }) { order += "b1" } }
            delay(50)
            scheduler.load() shouldBe (1 to 3)
            gate.complete(Unit)
        }
        order shouldContainExactly listOf("a0", "a1", "b1", "a2")
        waits shouldContainExactly listOf(1, 2, 3)
        scheduler.load() shouldBe (0 to 0)
    }

    test("요청 큐: 기다리다 취소되면 빠지고 자리가 새지 않는다") {
        val scheduler = FairScheduler(1)
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            launch { scheduler.run(UserId(1)) { gate.await() } }
            delay(30)
            val waiting = launch { scheduler.run(UserId(2)) { error("실행되면 안 됨") } }
            delay(30)
            waiting.cancel()
            delay(30)
            scheduler.load() shouldBe (1 to 0)
            gate.complete(Unit)
        }
        withTimeout(1_000) { scheduler.run(UserId(3)) { "ok" } } shouldBe "ok"
        scheduler.load() shouldBe (0 to 0)
    }

    test("요청 큐: 동시에 slots개까지 실행") {
        val scheduler = FairScheduler(2)
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            (1..6).map { i ->
                async {
                    scheduler.run(UserId(i % 3L)) {
                        peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(30)
                        running.decrementAndGet()
                    }
                }
            }.forEach { it.await() }
        }
        peak.get() shouldBe 2
    }

    test("사용자 관리: OWNER는 하나·바꿀 수 없음, 이름·비밀번호 규칙, 최대 인원, 학습 동의") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val service = UserService(ExposedUserRepository(db.db), WebAuthRepository(db.db), maxUsers = 3)
        shouldThrow<UserException> { service.create("boss", UserRole.OWNER, "password-0001") }
        shouldThrow<UserException> { service.create("a b", UserRole.MEMBER, "password-0001") }
        listOf(".", "..", ".hidden", "-x", "_x").forEach { shouldThrow<UserException> { service.create(it, UserRole.MEMBER, "password-0001") } }
        shouldThrow<UserException> { service.create("mina", UserRole.MEMBER, "short") }
        val mina = service.create("mina", UserRole.MEMBER, "password-0001")
        mina.hasPassword shouldBe true
        shouldThrow<UserException> { service.create("mina", UserRole.GUEST, null) }
        service.create("손님", UserRole.GUEST, null).hasPassword shouldBe false
        shouldThrow<UserException> { service.create("third", UserRole.GUEST, null) }
        shouldThrow<UserException> { service.setRole("owner", UserRole.MEMBER) }
        shouldThrow<UserException> { service.delete("owner") }
        service.setRole("mina", UserRole.GUEST).role shouldBe UserRole.GUEST

        service.trainingUsers() shouldBe setOf(owner)
        service.setConsent("mina", true)
        service.trainingUsers() shouldBe setOf(owner, mina.id)

        // 바깥 접속 이메일 (M26): 소문자로, 한 이메일은 한 사용자만
        service.setEmail("mina", " Mina@Example.com ").email shouldBe "mina@example.com"
        service.find("mina")!!.email shouldBe "mina@example.com"
        shouldThrow<UserException> { service.setEmail("owner", "mina@example.com") }
        shouldThrow<UserException> { service.setEmail("owner", "not-an-email") }
        service.setEmail("mina", null).email shouldBe null

        shouldThrow<UserException> { service.setOwnPassword(mina.id, "short") }
        service.setOwnPassword(mina.id, "password-0002")
        PasswordHasher.verify("password-0002", WebAuthRepository(db.db).credential("mina")!!.passwordHash!!) shouldBe true
    }

    test("사용자를 지우면 대화·컬렉션·공유가 함께 지워지고, 다른 사람 데이터는 남는다. 오늘 사용량은 사용자별") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        var mediaDeleted: UserId? = null
        val service = UserService(ExposedUserRepository(db.db), WebAuthRepository(db.db), deleteMedia = { mediaDeleted = it })
        val mina = service.create("mina", UserRole.MEMBER, "password-0001")
        val sessions = ExposedSessionRepository(db.db)
        val messages = ExposedMessageRepository(db.db)
        val knowledge = KnowledgeRepository(db.db)
        val ownerSession = sessions.create(owner, ChannelType.WEB, "주인")
        messages.append(ownerSession.id, NewMessage(ChatRole.USER, "주인 질문"))
        val minaSession = sessions.create(mina.id, ChannelType.WEB, "미나")
        messages.append(minaSession.id, NewMessage(ChatRole.USER, "미나 질문 1"))
        messages.append(minaSession.id, NewMessage(ChatRole.ASSISTANT, "답", completionTokens = 42))
        messages.append(minaSession.id, NewMessage(ChatRole.USER, "미나 질문 2"))
        val minaDocs = knowledge.createCollection(mina.id, "mina-notes", null, "embed")
        knowledge.replaceDocument(minaDocs.id, "/a.md", "a", "h", listOf(NewChunk("미나 메모", null, null, FloatArray(2))))
        val ownerDocs = knowledge.createCollection(owner, "family", null, "embed", CollectionKind.DOCS)
        knowledge.share(owner, ownerDocs.id, mina.id) shouldBe true

        service.usageToday().let {
            it[mina.id]!!.questions shouldBe 2
            it[mina.id]!!.completionTokens shouldBe 42
            it[owner]!!.questions shouldBe 1
        }

        service.delete("mina")
        mediaDeleted shouldBe mina.id
        service.find("mina") shouldBe null
        sessions.find(mina.id, minaSession.id) shouldBe null
        knowledge.listCollections(mina.id).shouldBeEmpty()
        knowledge.keywordSearch(listOf(minaDocs.id), "미나", 5).shouldBeEmpty()
        sessions.find(owner, ownerSession.id)!!.title shouldBe "주인"
        knowledge.accessibleCollections(owner).map { it.name } shouldBe listOf("family")
        knowledge.listCollections(owner).single().sharedWith.shouldBeEmpty()
    }
})
