package dev.vesta.conversation

import dev.vesta.core.AgentType

/**
 * 라우팅 1단계: 사용자가 직접 지정한 에이전트 (PROJECT.md 4.5).
 * - `/docs 질문` → 모든 컬렉션에서 문서 검색
 * - `@컬렉션 질문`, `@a,b 질문`, `@a @b 질문` → 지정한 컬렉션에서 문서 검색
 * - `/chat 질문` → 일반 대화
 * 그 밖에는 null (M4부터 규칙·결정 모델이 판단, 지금은 ChatAgent).
 */
data class ExplicitRoute(val agent: AgentType, val collections: List<String>, val question: String) {
    companion object {
        private val mention = Regex("^@([\\p{L}\\p{N}_.\\-]+(?:,[\\p{L}\\p{N}_.\\-]+)*)\\s*")
        private val commands = mapOf("/docs" to AgentType.DOCS, "/chat" to AgentType.CHAT)

        fun parse(text: String): ExplicitRoute? {
            val trimmed = text.trim()
            commands.entries.firstOrNull { (command, _) -> trimmed == command || trimmed.startsWith("$command ") }?.let { (command, agent) ->
                return ExplicitRoute(agent, emptyList(), trimmed.removePrefix(command).trim())
            }
            var rest = trimmed
            val collections = mutableListOf<String>()
            while (true) {
                val match = mention.find(rest) ?: break
                collections += match.groupValues[1].split(',')
                rest = rest.substring(match.range.last + 1)
            }
            return if (collections.isEmpty()) null else ExplicitRoute(AgentType.DOCS, collections.distinct(), rest.trim())
        }
    }
}
