package dev.vesta.agents

import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.DecisionQuestion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class AppGroupSelectorTest : FunSpec({
    val all = setOf("calendar", "reminder", "mail", "messages", "notes", "contacts", "music", "weather", "location", "shortcuts", "clock")

    test("낱말 규칙만으로도 고르고 시계는 늘 넣는다") {
        AppGroupSelector(null).select("안 읽은 메일 요약해 줘", all) shouldBe setOf("mail", "clock")
        AppGroupSelector(null).select("내일 일정 비어 있어?", all) shouldContainAll listOf("calendar", "clock")
    }

    test("결정 모델 확률 상위 그룹을 더한다 (최대 3개, 기준 미만은 뺀다)") {
        val decision = object : DecisionClient {
            override suspend fun decide(state: String, questions: Map<String, DecisionQuestion>) = mapOf(
                "app" to DecisionAnswer.Choice("weather", 0.7, mapOf("weather" to 0.7, "calendar" to 0.2, "music" to 0.05)),
            )
        }
        val groups = AppGroupSelector(decision).select("주말에 나들이 가도 될까?", all)
        groups shouldContainAll listOf("weather", "calendar", "clock")
        groups shouldNotContain "music"
    }

    test("아무것도 못 고르면 캘린더·미리 알림, 없는 그룹은 빼고") {
        AppGroupSelector(null).select("음…", setOf("calendar", "reminder", "clock")) shouldBe setOf("calendar", "reminder", "clock")
        AppGroupSelector(null).select("메일 확인", setOf("calendar", "clock")) shouldNotContain "mail"
    }
})
