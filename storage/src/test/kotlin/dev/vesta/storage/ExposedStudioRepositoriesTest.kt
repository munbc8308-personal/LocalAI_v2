package dev.vesta.storage

import dev.vesta.core.ModelRole
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.studio.CustomAgentDef
import dev.vesta.core.studio.RunStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory

class ExposedStudioRepositoriesTest : FunSpec({
    test("사용자 정의 에이전트: 사용자마다 따로, 같은 이름이면 고친다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "mina"; it[role] = UserRole.MEMBER } }.value.let(::UserId)
        val repo = ExposedCustomAgentRepository(db.db)
        repo.save(CustomAgentDef(owner, "reviewer", "코드 리뷰어", "문제만 짚는다", ModelRole.CODE, listOf("read_file")))
        repo.save(CustomAgentDef(other, "reviewer", "남의 것", ""))
        repo.save(CustomAgentDef(owner, "reviewer", "코드 리뷰어 2", "고친 지침", ModelRole.CODE, listOf("read_file", "list_dir")))
        repo.list(owner).single().let {
            it.description shouldBe "코드 리뷰어 2"
            it.tools shouldBe listOf("read_file", "list_dir")
            it.model shouldBe ModelRole.CODE
        }
        repo.find(other, "reviewer")?.description shouldBe "남의 것"
        repo.delete(other, "reviewer") shouldBe true
        repo.find(owner, "reviewer")?.instructions shouldBe "고친 지침"
        repo.list(other).shouldBeEmpty()
    }

    test("실행 기록: 단계 번호는 실행마다 1부터, 끝나지 못한 실행은 실패로 정리") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val runs = ExposedRunRepository(db.db)
        val a = runs.start(owner, null, "CHAT", null, "질문 하나")
        val b = runs.start(owner, null, "CHAT", null, "질문 둘")
        runs.add(a.id, "input", "질문 하나", null).seq shouldBe 1
        runs.add(a.id, "routed", "작업", null).seq shouldBe 2
        runs.add(b.id, "input", "질문 둘", null).seq shouldBe 1
        runs.setAgent(a.id, "TASK", "reviewer")
        runs.finish(a.id, RunStatus.DONE, "답")
        runs.get(a.id)!!.let {
            it.status shouldBe RunStatus.DONE
            it.customAgent shouldBe "reviewer"
            it.answer shouldBe "답"
            (it.finishedAt != null) shouldBe true
        }
        runs.events(a.id).map { it.kind } shouldBe listOf("input", "routed")
        runs.events(a.id, afterSeq = 1).map { it.kind } shouldBe listOf("routed")
        runs.list(owner, 10).map { it.id } shouldBe listOf(b.id, a.id)
        runs.abandonRunning() shouldBe 1
        runs.get(b.id)!!.status shouldBe RunStatus.FAILED
    }
})
