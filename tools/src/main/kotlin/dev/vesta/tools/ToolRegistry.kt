package dev.vesta.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.tools.Tool

/**
 * 쓸 수 있는 도구 모음과 승인 정책 (PROJECT.md T-03).
 * READ 자동, WRITE·EXEC 승인. NETWORK는 웹 검색이 켜져 있을 때만 주고(꺼져 있으면 도구 자체를 뺀다),
 * 이번 작업에서 로컬 데이터를 읽은 뒤([tainted])에는 승인을 받는다 (인젝션으로 로컬 내용이 밖으로 새는 것 방지).
 */
class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.spec.name }

    val all: List<Tool> get() = byName.values.toList()
    operator fun get(name: String): Tool? = byName[name]

    companion object {
        fun needsApproval(risk: RiskLevel, tainted: Boolean = false) =
            risk == RiskLevel.WRITE || risk == RiskLevel.EXEC || (risk == RiskLevel.NETWORK && tainted)
    }
}
