package dev.vesta.app

import dev.vesta.core.skill.Skill
import dev.vesta.core.skill.SkillSource
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/** 앱에 함께 든 스킬 (PROJECT.md 7.27 K-15): 리소스 `skills/index.txt`의 이름마다 `skills/<이름>/SKILL.md` (pstack, `scripts/vendor-pstack.py`) */
object BuiltinSkills {
    fun load(loader: ClassLoader = BuiltinSkills::class.java.classLoader): List<Skill> {
        fun read(path: String) = loader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
        return read("skills/index.txt").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { name ->
            read("skills/$name/SKILL.md")?.let { Skill.parse(name, it, SkillSource.BUILTIN) }
                .also { if (it == null) log.warn { "내장 스킬을 읽지 못함: $name" } }
        }
    }
}
