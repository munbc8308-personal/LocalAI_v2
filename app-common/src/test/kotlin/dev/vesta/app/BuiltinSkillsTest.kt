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
    test("기본 스킬 38개(pstack 31 + Vesta 7)가 모두 읽히고, 코드 원칙은 작업 에이전트·글 다듬기는 대화 에이전트 (M33 K-16·K-18)") {
        val skills = BuiltinSkills.load()
        skills.size shouldBe 38
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

    test("Vesta 코드 개발 스킬 7개: 모두 작업 에이전트, 리뷰·보안·커밋·PR은 쓰기 도구 없이") {
        val byName = BuiltinSkills.load().associateBy { it.name }
        listOf("review", "debug", "refactor", "patch", "commit", "pr", "security-review").forEach { name ->
            byName.getValue(name).agent shouldBe AgentType.TASK
            byName.getValue(name).description shouldContain "(Vesta 기본)"
            Skill.RESERVED.contains(name) shouldBe false
        }
        listOf("review", "security-review", "commit", "pr").forEach { byName.getValue(it).tools.contains("write_file") shouldBe false }
        listOf("debug", "refactor", "patch").forEach { byName.getValue(it).tools shouldContainAll listOf("apply_patch", "run_shell") }
        SkillCall.parse("/security-review app-server")?.name shouldBe "security-review"
    }

    test("웹에서 만드는 스킬 이름은 지금처럼 32자까지") {
        Skill.problem("a".repeat(33), "설명", "본문", "CHAT", emptyList(), setOf(AgentType.CHAT), emptySet()) shouldContain "1~32자"
        Skill.problem("a".repeat(32), "설명", "본문", "CHAT", emptyList(), setOf(AgentType.CHAT), emptySet()) shouldBe null
    }
})
