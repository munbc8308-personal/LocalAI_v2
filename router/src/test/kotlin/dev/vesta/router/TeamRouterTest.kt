package dev.vesta.router

import dev.vesta.core.config.TeamRoutingConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TeamRouterTest : FunSpec({
    // 낱말이 겹치는 만큼 비슷한 가짜 임베딩: 낱말마다 한 축
    val words = listOf("시트", "조사", "메일", "리뷰", "코드", "날씨", "설명", "그림")
    fun vector(text: String) = FloatArray(words.size + 1) { i -> if (i == words.size) 0.2f else if (words[i] in text) 1f else 0f }
    var calls = 0
    val router = TeamRouter({ "m" }, { _, texts -> calls += texts.size; texts.map(::vector) }, TeamRoutingConfig(auto = 0.7, autoMargin = 0.15, suggest = 0.5, suggestMargin = 0.05, minChars = 5),
        anchors = listOf("날씨 알려 줘", "설명해 줘", "그림 그려 줘"))
    val google = TeamCandidate("구글팀", true, listOf("구글팀: 조사해서 시트로", "자료 조사해서 구글 시트에 정리"), setOf("web_search", "sheets_create"))
    val review = TeamCandidate("리뷰팀", true, listOf("리뷰팀: 코드 리뷰"), setOf("read_file", "run_shell"))
    val helper = TeamCandidate("helper", false, listOf("helper: 메일 정리"), emptySet())

    test("예시와 비슷하면 그 팀에 맡기고, 일상 질문은 아무 팀도 아니다") {
        router.pick("통계 자료 조사해서 구글 시트로 만들어 줘", listOf(google, review))!!.let { it.name shouldBe "구글팀"; it.auto shouldBe true }
        router.pick("오늘 날씨 좀 알려 줘요", listOf(google, review)) shouldBe null
        router.pick("짧다", listOf(google)) shouldBe null
    }

    test("에이전트는 맡기지 않고 추천만, 필요한 도구가 없는 팀은 뺀다") {
        router.pick("받은 메일 정리 좀 해 줘", listOf(helper, review))!!.let { it.name shouldBe "helper"; it.auto shouldBe false }
        // 구글 시트가 필요한데 시트 도구가 있는 팀이 없다
        router.pick("코드 리뷰 결과를 구글 시트에 써 줘", listOf(review)) shouldBe null
        TeamRouter.required("구글 시트에 쓰고 테스트 돌려 줘") shouldBe listOf("sheets_", "run_shell")
    }

    test("후보 글 벡터는 한 번만 만든다") {
        calls = 0
        repeat(3) { router.pick("코드 리뷰 부탁해요", listOf(review)) }
        // 질문 3번 + 후보·기준점 글은 처음 한 번 (앞 테스트에서 이미 만든 것은 다시 안 만듦)
        (calls <= 3 + 1) shouldBe true
    }
})
