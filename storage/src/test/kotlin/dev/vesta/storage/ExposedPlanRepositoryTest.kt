package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.plan.PlanCheck
import dev.vesta.core.plan.PlanItem
import dev.vesta.core.plan.PlanStatus
import dev.vesta.core.plan.TaskPlan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class ExposedPlanRepositoryTest : FunSpec({
    test("작업 계획 (M32): 대화마다 하나, 바꾸면 덮어씀, 글 안의 |·줄바꿈도 안전, 대화를 지우면 함께 지움") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val sessions = ExposedSessionRepository(db.db)
        val repo = ExposedPlanRepository(db.db)
        val s = sessions.create(owner, ChannelType.CLI, "t")
        repo.get(s.id) shouldBe null
        val plan = TaskPlan("가계부 고치기", listOf(PlanItem("a|b 쓰기", PlanStatus.IN_PROGRESS), PlanItem("빌드\n통과", PlanStatus.PENDING, PlanCheck.BUILD)))
        repo.save(s.id, plan)
        repo.get(s.id)!!.let { it.goal shouldBe "가계부 고치기"; it.items shouldBe listOf(PlanItem("a|b 쓰기", PlanStatus.IN_PROGRESS), PlanItem("빌드 통과", PlanStatus.PENDING, PlanCheck.BUILD)) }
        repo.save(s.id, plan.copy(items = listOf(PlanItem("c", PlanStatus.DONE))))
        repo.get(s.id)!!.items.single().text shouldBe "c"
        sessions.delete(owner, s.id)
        repo.get(s.id) shouldBe null
    }
})
