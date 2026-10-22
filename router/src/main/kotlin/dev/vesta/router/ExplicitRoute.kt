package dev.vesta.router

import dev.vesta.core.AgentType

/**
 * 라우팅 1~2단계 (PROJECT.md 4.5).
 * 1단계 명시 지정:
 * - `/docs 질문` → 모든 컬렉션에서 문서 검색
 * - `@컬렉션 질문`, `@a,b 질문`, `@a @b 질문` → 지정한 컬렉션에서 문서 검색
 * - `/web 질문` → 웹 검색
 * - `/chat 질문` → 일반 대화
 * - `/image 그릴 것` → 이미지 생성 (M13)
 * - `/code 질문` → 코드 도우미, `/task 할 일` → 작업 에이전트(파일·셸 도구) (M17)
 * 2단계 확실한 규칙:
 * - 질문에 http(s) URL이 있으면 → 웹 (그 페이지를 읽는다, W-09)
 * 그 밖에는 null (M4부터 결정 모델이 판단, 지금은 ChatAgent).
 */
data class ExplicitRoute(val agent: AgentType, val collections: List<String>, val question: String) {
    companion object {
        private val mention = Regex("^@([\\p{L}\\p{N}_.\\-]+(?:,[\\p{L}\\p{N}_.\\-]+)*)\\s*")
        private val commands = mapOf("/docs" to AgentType.DOCS, "/web" to AgentType.WEB, "/chat" to AgentType.CHAT, "/image" to AgentType.IMAGE, "/code" to AgentType.CODE, "/task" to AgentType.TASK)
        private val url = Regex("https?://\\S+")

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
            if (collections.isNotEmpty()) return ExplicitRoute(AgentType.DOCS, collections.distinct(), rest.trim())
            return if (url.containsMatchIn(trimmed)) ExplicitRoute(AgentType.WEB, emptyList(), trimmed) else null
        }
    }
}
