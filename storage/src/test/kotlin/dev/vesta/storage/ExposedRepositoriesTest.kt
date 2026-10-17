package dev.vesta.storage

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.NewMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.io.path.createTempDirectory

class ExposedRepositoriesTest : FunSpec({
    fun setup(): Triple<VestaDatabase, ExposedSessionRepository, ExposedMessageRepository> {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        return Triple(db, ExposedSessionRepository(db.db), ExposedMessageRepository(db.db))
    }

    test("세션을 만들고 메시지를 순서대로 저장·조회한다") {
        val (db, sessions, messages) = setup()
        val owner = db.ensureOwner("owner")
        val session = sessions.create(owner, ChannelType.CLI, "첫 대화")

        messages.append(session.id, NewMessage(ChatRole.USER, "안녕"))
        messages.append(session.id, NewMessage(ChatRole.ASSISTANT, "안녕하세요", AgentType.CHAT, "m", interrupted = true))

        val stored = messages.list(session.id)
        stored.map { it.role to it.content } shouldBe listOf(ChatRole.USER to "안녕", ChatRole.ASSISTANT to "안녕하세요")
        stored[1].agent shouldBe AgentType.CHAT
        stored[1].interrupted shouldBe true
        sessions.find(owner, session.id)?.title shouldBe "첫 대화"
    }

    test("다른 사용자의 세션은 조회되지 않는다") {
        val (db, sessions, _) = setup()
        val owner = db.ensureOwner("owner")
        val session = sessions.create(owner, ChannelType.CLI, null)
        sessions.find(UserId(owner.value + 999), session.id) shouldBe null
        sessions.listRecent(UserId(owner.value + 999), 10).shouldBeEmpty()
    }

    test("최근 사용 순으로 정렬된다") {
        val (db, sessions, messages) = setup()
        val owner = db.ensureOwner("owner")
        val older = sessions.create(owner, ChannelType.CLI, "old")
        val newer = sessions.create(owner, ChannelType.CLI, "new")
        messages.append(older.id, NewMessage(ChatRole.USER, "다시 씀"))
        sessions.listRecent(owner, 10).first().id shouldBe older.id
        newer.id shouldNotBe older.id
    }
})
