package dev.vesta.app

import dev.vesta.core.AgentType
import dev.vesta.core.skill.Skill
import dev.vesta.core.skill.SkillCall
import dev.vesta.core.skill.SkillSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BuiltinSkillsTest : FunSpec({
    test("pstack 31개가 모두 읽히고, 코드 원칙은 작업 에이전트·글 다듬기는 대화 에이전트 (M33 K-16·K-18)") {
        val skills = BuiltinSkills.load()
        skills.size shouldBe 31
        skills.map { it.source }.toSet() shouldBe setOf(SkillSource.BUILTIN)
        val byName = skills.associateBy { it.name }
        byName.getValue("tdd").agent shouldBe AgentType.TASK
        byName.getValue("tdd").tools shouldContainAll listOf("read_file", "run_shell")
        byName.getValue("unslop").agent shouldBe AgentType.CHAT
        byName.getValue("unslop").tools shouldBe emptyList()
        // 다른 스킬 파일 링크는 /이름 으로
        byName.getValue("principle-attack-the-premise").body.let { it shouldContain "Build the Lever(/principle-build-the-lever)"; it shouldNotContain "SKILL.md" }
        // 50자 원본 이름도 그대로 부를 수 있다
        SkillCall.parse("/principle-separate-before-serializing-shared-state 이 코드 봐 줘")?.name shouldBe "principle-separate-before-serializing-shared-state"
        byName["principle-separate-before-serializing-shared-state"] shouldNotBe null
    }

    test("웹에서 만드는 스킬 이름은 지금처럼 32자까지") {
        Skill.problem("a".repeat(33), "설명", "본문", "CHAT", emptyList(), setOf(AgentType.CHAT), emptySet()) shouldContain "1~32자"
        Skill.problem("a".repeat(32), "설명", "본문", "CHAT", emptyList(), setOf(AgentType.CHAT), emptySet()) shouldBe null
    }
})
