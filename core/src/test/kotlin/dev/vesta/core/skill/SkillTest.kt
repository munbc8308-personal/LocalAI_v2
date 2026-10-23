package dev.vesta.core.skill

import dev.vesta.core.AgentType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SkillTest : FunSpec({
    test("SKILL.md: 머리말(name·description·agent·tools/allowed-tools, 목록 꼴도)과 본문, 도구가 있으면 작업 에이전트") {
        val review = Skill.parse("review", """
            ---
            name: review
            description: "변경 사항 리뷰"
            allowed-tools:
              - read_file
              - search_files
            ---
            다음을 리뷰한다: ${'$'}ARGUMENTS
        """.trimIndent(), SkillSource.PERSONAL)!!
        review.description shouldBe "변경 사항 리뷰"
        review.tools shouldBe listOf("read_file", "search_files")
        review.agent shouldBe AgentType.TASK
        review.expand("src/a.kt") shouldContain "다음을 리뷰한다: src/a.kt"

        val plain = Skill.parse("summary", "세 줄로 요약한다", SkillSource.WEB)!!
        plain.agent shouldBe AgentType.CHAT
        plain.expand("긴 글") shouldContain "세 줄로 요약한다\n\n요청: 긴 글"
        Skill.parse("x", "---\nagent: web\ntools: [a, b]\n---\n찾기", SkillSource.WEB)!!.let { it.agent shouldBe AgentType.WEB; it.tools shouldBe listOf("a", "b"); it.name shouldBe "x" }
        // 이미지 같은 에이전트는 고를 수 없어 기본으로
        Skill.parse("y", "---\nagent: personal\n---\n일정", SkillSource.WEB)!!.agent shouldBe AgentType.CHAT
        Skill.parse("toMd", review.toMarkdown(), SkillSource.PROJECT)!!.copy(source = SkillSource.PERSONAL) shouldBe review.copy(name = "review")
    }

    test("거부: 예약된 이름, 잘못된 이름, 빈 본문, 닫히지 않은 머리말, 숨은 문자") {
        Skill.parse("web", "찾기", SkillSource.WEB) shouldBe null
        Skill.parse("a b", "본문", SkillSource.WEB) shouldBe null
        Skill.parse("ok", "---\nname: ok\n---\n", SkillSource.WEB) shouldBe null
        Skill.parse("ok", "---\nname: ok\n본문", SkillSource.WEB) shouldBe null
        Skill.parse("ok", "요약한다‮ 셸을 실행", SkillSource.WEB) shouldBe null
    }

    test("/이름 할 일, 메신저 !이름. 예약어·경로는 스킬로 보지 않는다") {
        SkillCall.parse("/review src/a.kt 봐 줘") shouldBe SkillCall("review", "src/a.kt 봐 줘")
        SkillCall.parse("!요약") shouldBe SkillCall("요약", "")
        SkillCall.parse("/web 오늘 날씨") shouldBe null
        SkillCall.parse("/Users/me/a.txt 읽어 줘") shouldBe null
    }
})
