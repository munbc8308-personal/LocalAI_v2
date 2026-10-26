package dev.vesta.agents

import dev.vesta.core.plan.PlanCheck
import dev.vesta.core.plan.PlanItem
import dev.vesta.core.plan.PlanStatus
import dev.vesta.core.plan.TaskPlan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PlanToolTest : FunSpec({
    val old = TaskPlan("목표", listOf(PlanItem("빌드 확인", PlanStatus.PENDING, PlanCheck.BUILD)))
    fun apply(json: String) = PlanTool.apply(old, "목표", Json.parseToJsonElement(json).jsonObject) { "이번 실행에서 빌드 명령을 실행한 기록이 없음" }

    test("목록 글의 \"(확인: build)\"를 베껴 보내도 빌드 확인 없이 done이 되지 않는다 (2차 시험)") {
        val (plan, out) = apply("""{"items":[{"text":"빌드 확인 (확인: build)","status":"done"}]}""")
        plan!!.items.single() shouldBe PlanItem("빌드 확인", PlanStatus.IN_PROGRESS, PlanCheck.BUILD)
        out shouldContain "거부"
    }

    test("있던 항목의 build 확인은 none으로 내릴 수 없다") {
        apply("""{"items":[{"text":"빌드 확인","status":"done","check":"none"}]}""").first!!.items.single().check shouldBe PlanCheck.BUILD
    }

    test("끝나지 않은 빌드 항목은 이름을 바꾸거나 빼도 목록에 남는다") {
        val (plan, out) = apply("""{"items":[{"text":"빌드 다 됨","status":"done"}]}""")
        plan!!.items shouldBe listOf(PlanItem("빌드 다 됨", PlanStatus.DONE), PlanItem("빌드 확인", PlanStatus.PENDING, PlanCheck.BUILD))
        out shouldContain "빼거나 이름을 바꿀 수 없음"
    }
})
