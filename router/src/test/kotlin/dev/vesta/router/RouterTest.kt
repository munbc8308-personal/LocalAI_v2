package dev.vesta.router

import dev.vesta.core.AgentType
import dev.vesta.core.config.DecisionConfig
import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.DecisionQuestion
import dev.vesta.core.decision.RoutingStage
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class FakeDecision(val agent: String, val confidence: Double, val web: Double = 0.0) : DecisionClient {
    var calls = 0
    override suspend fun decide(state: String, questions: Map<String, DecisionQuestion>): Map<String, DecisionAnswer> {
        calls++
        return mapOf(
            "agent" to DecisionAnswer.Choice(agent, confidence, emptyMap()),
            "needsWeb" to DecisionAnswer.Noul(web),
            "needsLocal" to DecisionAnswer.Noul(0.0),
        )
    }
}

class RouterTest : FunSpec({
    val all = AgentType.entries.toSet()
    val basic = setOf(AgentType.CHAT, AgentType.DOCS, AgentType.WEB)

    test("명시 지정·URL·코드 블록은 모델 없이 정한다") {
        val decision = FakeDecision("CHAT", 0.99)
        val router = Router(DecisionConfig(), decision, null)
        router.route("/web 환율", emptyList(), all).let { it.agent shouldBe AgentType.WEB; it.stage shouldBe RoutingStage.EXPLICIT; it.question shouldBe "환율" }
        router.route("/code 이 함수 설명해 줘", emptyList(), all).let { it.agent shouldBe AgentType.CODE; it.question shouldBe "이 함수 설명해 줘" }
        router.route("/task 테스트 돌려 줘", emptyList(), all).let { it.agent shouldBe AgentType.TASK; it.stage shouldBe RoutingStage.EXPLICIT }
        router.route("요약해 줘 https://a.com", emptyList(), all).stage shouldBe RoutingStage.RULE
        router.route("```kotlin\nfun f() {}\n``` 리뷰", emptyList(), all).agent shouldBe AgentType.CODE
        decision.calls shouldBe 0
    }

    test("영상 만들기 (M24): /video, 확실한 말은 규칙, 그림을 고른 영상 요청은 영상으로. 영상을 다루는 코드·요약은 아님") {
        val decision = FakeDecision("CHAT", 0.99)
        val router = Router(DecisionConfig(), decision, null)
        router.route("/video 제주 여행 30초", emptyList(), all).let { it.agent shouldBe AgentType.VIDEO; it.stage shouldBe RoutingStage.EXPLICIT }
        listOf("우리 동네 맛집 소개 30초 영상 만들어 줘", "고양이 쇼츠 하나 제작해 줘", "가을 풍경 슬라이드쇼 만들어줘").forEach {
            router.route(it, emptyList(), all).let { r -> r.agent shouldBe AgentType.VIDEO; r.stage shouldBe RoutingStage.RULE }
        }
        decision.calls shouldBe 0
        listOf("파이썬으로 동영상 편집하는 코드 만들어 줘", "회의 영상 요약 만들어 줘", "영상 편집 어떻게 만들어?").forEach {
            router.route(it, emptyList(), all).agent shouldBe AgentType.CHAT
        }
        Router(DecisionConfig(), FakeDecision("IMAGE", 0.9), null).route("여행 브이로그 느낌으로 하나 부탁해", emptyList(), all).agent shouldBe AgentType.VIDEO
        Router(DecisionConfig(), FakeDecision("IMAGE", 0.9), null).route("여행 사진 느낌으로 하나 그려 줘", emptyList(), all).agent shouldBe AgentType.IMAGE
    }

    test("결정 모델 신뢰도가 임계값 이상이면 그대로 쓴다") {
        val result = Router(DecisionConfig(), FakeDecision("WEB", 0.8), null).route("오늘 환율 얼마야?", emptyList(), all)
        result.agent shouldBe AgentType.WEB
        result.stage shouldBe RoutingStage.DECISION_MODEL
    }

    test("신뢰도가 낮으면 다음 단계, 메인 모델이 없으면 일반 대화") {
        val result = Router(DecisionConfig(), FakeDecision("TASK", 0.3), null).route("오늘 환율 얼마야?", emptyList(), all)
        result.agent shouldBe AgentType.CHAT
        result.stage shouldBe RoutingStage.DEFAULT
    }

    test("아직 없는 에이전트면 대신 쓰고 원래 판정을 남긴다. 웹 필요가 높으면 웹으로") {
        val personal = Router(DecisionConfig(), FakeDecision("PERSONAL", 0.9), null).route("내일 일정 알려줘", emptyList(), basic)
        personal.agent shouldBe AgentType.CHAT
        personal.requested shouldBe AgentType.PERSONAL
        Router(DecisionConfig(), FakeDecision("PERSONAL", 0.9, web = 0.9), null).route("내일 날씨", emptyList(), basic).agent shouldBe AgentType.WEB
    }

    test("짧은 후속 질문은 결정 모델을 건너뛴다") {
        val decision = FakeDecision("WEB", 0.99)
        Router(DecisionConfig(), decision, null).route("그거 더 자세히", listOf(ChatMessage(ChatRole.USER, "환율")), all)
        decision.calls shouldBe 0
    }

    test("짧은 후속 질문은 직전 에이전트·컬렉션을 이어받는다") {
        val previous = dev.vesta.core.decision.RoutingDecision(AgentType.DOCS, 1.0, RoutingStage.EXPLICIT, collections = listOf("b"), question = "메인 모델은?")
        val recent = listOf(ChatMessage(ChatRole.USER, "메인 모델은?"), ChatMessage(ChatRole.ASSISTANT, "gemma4"))
        val router = Router(DecisionConfig(), FakeDecision("CHAT", 0.99), null)
        router.route("그럼 코드 모델은?", recent, all, previous).let { it.agent shouldBe AgentType.DOCS; it.collections shouldBe listOf("b") }
        router.route("코드 모델은?", recent, all, previous).agent shouldBe AgentType.DOCS
        router.route("파이썬으로 웹 서버 만드는 법 자세히 알려줘", recent, all, previous).agent shouldBe AgentType.CHAT // 새 주제
    }

    test("메인 모델 JSON 판정 해석") {
        LlmRouter.parse("""{"agent": "web", "confidence": 0.9, "needsWeb": true, "needsLocal": false}""") shouldBe
            LlmVerdict(AgentType.WEB, 0.9, needsWeb = true, needsLocal = false)
        LlmRouter.parse("모르겠음") shouldBe null
        LlmRouter.parse("""{"agent": "UNKNOWN"}""") shouldBe null
    }

    test("/image는 이미지 생성, 그 뒤 짧은 수정 요청은 이미지를 이어서 다시 그린다. 이미지 생성이 없으면 일반 대화") {
        val router = Router(DecisionConfig(), FakeDecision("CHAT", 0.99), null)
        val first = router.route("/image 고양이 수채화", emptyList(), all)
        first.agent shouldBe AgentType.IMAGE
        first.question shouldBe "고양이 수채화"
        router.route("더 밝게 해 줘", listOf(ChatMessage(ChatRole.USER, "/image 고양이 수채화")), all, first).agent shouldBe AgentType.IMAGE
        router.route("/image 고양이", emptyList(), basic).agent shouldBe AgentType.CHAT
    }
})
