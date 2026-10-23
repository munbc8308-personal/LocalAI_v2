package dev.vesta.core.skill

import dev.vesta.core.AgentType
import dev.vesta.core.UserId
import java.time.Instant

/** 스킬이 어디서 왔는가: 작업 폴더(`.vesta/skills`, CLI), 내 파일(`~/.vesta/skills`, Mac 주인), 웹에서 만든 것 */
enum class SkillSource { PROJECT, PERSONAL, WEB }

/**
 * 스킬 (PROJECT.md K-01, M22): 자주 하는 일의 지침. `/이름 할 일`로 부르면 [body]의 `$ARGUMENTS`를 할 일로 바꿔 [agent]에게 맡긴다.
 * [tools]는 작업 에이전트(TASK)일 때 쓸 도구 이름: 실제로는 그 사용자 역할이 쓸 수 있는 도구와 겹치는 것만 (권한을 넓히지 못한다, K-06)
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val agent: AgentType = AgentType.CHAT,
    val tools: List<String> = emptyList(),
    val source: SkillSource = SkillSource.WEB,
    val updatedAt: Instant = Instant.EPOCH,
) {
    /** 이번 턴에 에이전트에게 줄 글: `$ARGUMENTS`를 할 일로, 없으면 지침 뒤에 할 일을 붙인다 */
    fun expand(arguments: String): String {
        val args = arguments.trim()
        val text = if (ARGUMENTS in body) body.replace(ARGUMENTS, args) else body.trimEnd() + if (args.isNotEmpty()) "\n\n요청: $args" else ""
        return "[스킬 /$name — $description]\n$text"
    }

    /** SKILL.md 꼴로 (CLI가 서버로 보낼 때, 승인 화면) */
    fun toMarkdown(): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: ${description.replace('\n', ' ')}")
        appendLine("agent: ${agent.name.lowercase()}")
        if (tools.isNotEmpty()) appendLine("tools: ${tools.joinToString(", ")}")
        appendLine("---")
        append(body)
    }

    companion object {
        const val ARGUMENTS = "\$ARGUMENTS"
        val NAME = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,31}$")
        const val MAX_BODY = 8_000
        const val MAX_DESCRIPTION = 300
        /** 스킬로 쓸 수 있는 에이전트 */
        val AGENTS = setOf(AgentType.CHAT, AgentType.TASK, AgentType.CODE, AgentType.WEB, AgentType.DOCS)

        /** 이미 쓰는 명령: 라우팅(/web …), 스튜디오(/agent·/team·/skills·/agents), 메신저 연결(/start), CLI 명령(/exit …) */
        val RESERVED = setOf(
            "web", "docs", "chat", "image", "code", "task", "agent", "team", "skills", "agents", "start",
            "exit", "quit", "help", "new", "sessions", "resume", "history", "accept",
        )

        /**
         * SKILL.md 읽기 (Claude Code와 같은 꼴): `---` 사이의 머리말(`name`·`description`·`agent`·`tools` 또는 `allowed-tools`) + 본문.
         * 이름은 머리말 것이 맞으면 그것, 아니면 폴더 이름. 형식이 틀리거나 숨은 문자가 있으면 null
         */
        fun parse(folder: String, text: String, source: SkillSource, updatedAt: Instant = Instant.EPOCH): Skill? {
            if (dev.vesta.core.text.HiddenChars.has(text)) return null
            val lines = text.replace("\r\n", "\n").lines()
            val meta = mutableMapOf<String, String>()
            var listKey: String? = null
            val bodyStart = if (lines.firstOrNull()?.trim() == "---") {
                val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) ?: return null
                for (line in lines.subList(1, end)) {
                    val item = Regex("^\\s*-\\s*(.+)$").find(line)
                    if (item != null && listKey != null) { meta[listKey] = listOfNotNull(meta[listKey]?.ifBlank { null }, item.groupValues[1].trim()).joinToString(","); continue }
                    val kv = Regex("^([A-Za-z][A-Za-z_-]*)\\s*:\\s*(.*)$").find(line) ?: continue
                    listKey = kv.groupValues[1].lowercase()
                    meta[listKey] = kv.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                }
                end + 1
            } else 0
            val body = lines.drop(bodyStart).joinToString("\n").trim()
            if (body.isEmpty() || body.length > MAX_BODY) return null
            val name = meta["name"]?.takeIf { NAME.matches(it) } ?: folder.takeIf { NAME.matches(it) } ?: return null
            if (name.lowercase() in RESERVED) return null
            val tools = (meta["tools"] ?: meta["allowed-tools"]).orEmpty().removeSurrounding("[", "]")
                .split(',', ' ').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }.filter { it.isNotEmpty() }.distinct()
            val agent = meta["agent"]?.uppercase()?.let { runCatching { AgentType.valueOf(it) }.getOrNull() }?.takeIf { it in AGENTS }
                ?: if (tools.isNotEmpty()) AgentType.TASK else AgentType.CHAT
            return Skill(name, meta["description"].orEmpty().take(MAX_DESCRIPTION).ifBlank { body.lineSequence().first().take(80) }, body, agent, tools, source, updatedAt)
        }
    }
}

/** 웹에서 만든 스킬 (사용자별) */
interface SkillRepository {
    suspend fun list(owner: UserId): List<Skill>
    suspend fun find(owner: UserId, name: String): Skill?
    suspend fun save(owner: UserId, skill: Skill): Skill
    suspend fun delete(owner: UserId, name: String): Boolean
}

/** `/이름 할 일` (메신저는 `!이름`) */
data class SkillCall(val name: String, val arguments: String) {
    companion object {
        private val PATTERN = Regex("^[/!]([\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,31})(?:\\s+(.*))?$", RegexOption.DOT_MATCHES_ALL)
        fun parse(text: String): SkillCall? = PATTERN.find(text.trim())?.let { SkillCall(it.groupValues[1], it.groupValues[2].trim()) }
            ?.takeIf { it.name.lowercase() !in Skill.RESERVED }
    }
}
