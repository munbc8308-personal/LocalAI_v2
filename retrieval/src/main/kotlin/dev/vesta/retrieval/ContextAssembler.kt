package dev.vesta.retrieval

import dev.vesta.core.knowledge.Evidence

data class AssembledContext(val text: String, val evidence: List<Evidence>)

/**
 * 근거에 출처 번호 [n]을 붙여 글자 수 예산 안에 배치한다 (PROJECT.md 5.1 [4]).
 * 예산을 넘는 근거는 넣지 않으며, 번호는 실제로 넣은 근거에만 붙인다.
 */
object ContextAssembler {
    fun assemble(evidence: List<Evidence>, budgetChars: Int = 12_000): AssembledContext {
        val used = mutableListOf<Evidence>()
        val text = StringBuilder()
        for (item in evidence) {
            val number = used.size + 1
            val block = "[$number] ${item.title}${item.location?.let { " ($it)" }.orEmpty()}\n${item.text.trim()}\n\n"
            if (used.isNotEmpty() && text.length + block.length > budgetChars) break
            text.append(block)
            used += item.copy(citation = number)
        }
        return AssembledContext(text.toString().trimEnd(), used)
    }
}
