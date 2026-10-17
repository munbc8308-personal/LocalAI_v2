package dev.vesta.cli

import dev.vesta.core.knowledge.Evidence

/** 답변 본문에 나온 [n] 번호에 해당하는 근거만 고른다. [1][3], [1, 3] 형식 모두 인식한다. */
internal object Citations {
    private val bracket = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")

    fun numbers(answer: String): Set<Int> =
        bracket.findAll(answer).flatMap { m -> m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() } }.toSet()

    fun cited(answer: String, sources: List<Evidence>): List<Evidence> {
        val used = numbers(answer)
        return sources.filter { it.citation in used }
    }
}
