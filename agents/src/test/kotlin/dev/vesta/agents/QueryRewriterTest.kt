package dev.vesta.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class QueryRewriterTest : FunSpec({
    test("JSON·코드 블록·앞뒤 문장이 섞여도 검색어를 꺼낸다") {
        QueryRewriter.parse("""{"queries": ["Kotlin 2.4 새 기능", "Kotlin 2.4 release notes"]}""") shouldBe
            listOf("Kotlin 2.4 새 기능", "Kotlin 2.4 release notes")
        QueryRewriter.parse("검색어입니다:\n```json\n{\"queries\":[\"서울 날씨 내일\", \" 서울 날씨 내일 \"]}\n```") shouldBe listOf("서울 날씨 내일")
    }

    test("형식이 틀리면 빈 목록 (호출한 쪽이 질문 그대로 쓴다)") {
        QueryRewriter.parse("그냥 문장").shouldBeEmpty()
        QueryRewriter.parse("""{"q": 1}""").shouldBeEmpty()
    }
})
