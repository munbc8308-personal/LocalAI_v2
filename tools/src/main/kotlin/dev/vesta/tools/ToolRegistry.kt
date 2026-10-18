package dev.vesta.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.tools.Tool

/**
 * 쓸 수 있는 도구 모음과 승인 정책 (PROJECT.md T-03).
 * READ 자동, NETWORK는 웹 검색이 켜져 있을 때 자동(꺼져 있으면 도구 자체를 빼서 모델이 못 부른다), WRITE·EXEC는 승인.
 */
class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.spec.name }

    val all: List<Tool> get() = byName.values.toList()
    operator fun get(name: String): Tool? = byName[name]

    companion object {
        fun needsApproval(risk: RiskLevel) = risk == RiskLevel.WRITE || risk == RiskLevel.EXEC
    }
}
