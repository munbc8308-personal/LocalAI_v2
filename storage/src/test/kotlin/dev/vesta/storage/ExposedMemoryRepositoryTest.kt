package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.memory.MemoryKind
import dev.vesta.core.memory.MemorySource
import dev.vesta.core.session.NewMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory

class ExposedMemoryRepositoryTest : FunSpec({
    test("기억: 사용자 범위로만 보고 고치고 지운다, 벡터·쓴 횟수, 질문 읽기·커서, 사용자를 지우면 함께") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "mina"; it[role] = dev.vesta.core.UserRole.MEMBER } }.value.let(::UserId)
        val repo = ExposedMemoryRepository(db.db)
        val a = repo.add(owner, "나는 녹차를 좋아한다", MemoryKind.PREFERENCE, MemorySource.EXPLICIT, floatArrayOf(1f, 0f), "bge-m3")
        val b = repo.add(other, "나는 부산에 산다", MemoryKind.PROFILE, MemorySource.AUTO, null, null)
        repo.stored(owner).single().let { it.vector!!.toList() shouldBe listOf(1f, 0f); it.model shouldBe "bge-m3" }
        repo.update(other, a.id, "남이 고침", null, null, null) shouldBe false
        repo.update(owner, a.id, "나는 홍차를 좋아한다", null, floatArrayOf(0f, 1f), "bge-m3") shouldBe true
        repo.list(owner).single().let { it.text shouldBe "나는 홍차를 좋아한다"; it.kind shouldBe MemoryKind.PREFERENCE }
        repo.touch(listOf(a.id))
        repo.list(owner).single().let { it.uses shouldBe 1; (it.lastUsedAt != null) shouldBe true }
        repo.delete(owner, b.id) shouldBe false

        val sessions = ExposedSessionRepository(db.db)
        val messages = ExposedMessageRepository(db.db)
        val s = sessions.create(owner, ChannelType.WEB, "t")
        val q1 = messages.append(s.id, NewMessage(ChatRole.USER, "나는 판교에서 일해"))
        messages.append(s.id, NewMessage(ChatRole.ASSISTANT, "그렇군요"))
        val q2 = messages.append(s.id, NewMessage(ChatRole.USER, "고마워"))
        repo.questionsAfter(0, 10).map { it.id to it.text } shouldBe listOf(q1.id to "나는 판교에서 일해", q2.id to "고마워")
        repo.questionsAfter(q1.id, 10).single().user shouldBe owner
        repo.cursor() shouldBe 0L
        repo.setCursor(q2.id)
        repo.cursor() shouldBe q2.id

        ExposedUserRepository(db.db).delete(other) shouldBe true
        repo.stored(other).size shouldBe 0
        repo.clear(owner) shouldBe 1
        db.schemaVersion() shouldBe VestaDatabase.CURRENT_VERSION
    }
})
