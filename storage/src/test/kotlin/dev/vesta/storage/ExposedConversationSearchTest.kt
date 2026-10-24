package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.NewMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory

class ExposedConversationSearchTest : FunSpec({
    test("지난 대화 찾기 (M30): 조사가 붙어도·2글자도 찾고, 많이 맞은 대화부터, 짝 답, 본인 것만, 명령 턴·지금 대화는 빼고, 지운 대화는 색인에서도 빠진다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "mina"; it[role] = dev.vesta.core.UserRole.MEMBER } }.value.let(::UserId)
        val sessions = ExposedSessionRepository(db.db)
        val messages = ExposedMessageRepository(db.db)
        val search = ExposedConversationSearch(db.db)
        suspend fun talk(user: UserId, title: String, q: String, a: String, model: String? = "m") = sessions.create(user, ChannelType.WEB, title).also {
            messages.append(it.id, NewMessage(ChatRole.USER, q))
            messages.append(it.id, NewMessage(ChatRole.ASSISTANT, a, model = model))
        }
        val sort = talk(owner, "정렬", "파이썬에서 리스트를 정렬하는 방법", "sorted(리스트)나 list.sort()를 쓰면 됩니다.")
        val tea = talk(owner, "차", "녹차를 맛있게 우리는 법", "80도 물에 2분 우립니다.")
        talk(owner, "명령", "/recall 파이썬", "'파이썬'이 나온 지난 대화", model = "policy")
        talk(other, "남의 것", "파이썬 정렬 질문", "남의 답")
        val now = talk(owner, "지금", "지난번에 얘기한 파이썬 정렬 뭐였지?", "")

        search.search(owner, listOf("파이썬", "정렬"), exclude = now.id).let { hits ->
            hits.map { it.title } shouldBe listOf("정렬")
            hits.single().let { it.matched shouldBe 2; it.answer!! shouldContain "sorted"; it.question!! shouldContain "리스트" }
        }
        search.search(owner, listOf("녹차"), exclude = now.id).single().let { it.session shouldBe tea.id; it.snippet shouldContain "녹차를" }
        search.search(owner, listOf("우립니다")).single().question!! shouldContain "녹차"
        search.search(owner, listOf("파이썬", "녹차"), exclude = now.id).map { it.title }.toSet() shouldBe setOf("정렬", "차")
        search.search(other, listOf("녹차")) shouldBe emptyList()
        search.search(owner, listOf("x")) shouldBe emptyList()
        sessions.delete(owner, sort.id) shouldBe true
        search.search(owner, listOf("정렬"), exclude = now.id) shouldBe emptyList()
        ExposedConversationSearch.snippet("가".repeat(100) + "녹차" + "나".repeat(200), listOf("녹차")).let { it.startsWith("…") shouldBe true; it shouldContain "녹차" }
    }
})
