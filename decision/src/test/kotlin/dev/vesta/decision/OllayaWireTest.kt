package dev.vesta.decision

import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionQuestion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OllayaWireTest : FunSpec({
    test("요청 형식") {
        val body = OllayaWire.request(
            "laya", "질문",
            mapOf("agent" to DecisionQuestion.Choice("who?", mapOf("CHAT" to "c")), "web" to DecisionQuestion.Noul("web?")),
        ).toString()
        body shouldContain "\"type\":\"choice\""
        body shouldContain "\"criteria\":{\"CHAT\":\"c\"}"
        body shouldContain "\"type\":\"noul\""
    }

    test("Ollaya 문서의 응답 예시를 해석한다") {
        val answers = OllayaWire.answers(
            """
            {"model":"laya:en","answers":{
              "department":{"type":"choice","choice":"billing","confidence":0.7781,"probabilities":{"billing":0.8521,"technical":0.0611}},
              "urgency":{"type":"score","score":1.1982,"confidence":0.3418},
              "refund":{"type":"noul","noul":0.9127}}}
            """.trimIndent(),
        )
        answers["department"] shouldBe DecisionAnswer.Choice("billing", 0.7781, mapOf("billing" to 0.8521, "technical" to 0.0611))
        answers["urgency"] shouldBe DecisionAnswer.Score(1.1982, 0.3418)
        answers["refund"] shouldBe DecisionAnswer.Noul(0.9127)
    }
})
