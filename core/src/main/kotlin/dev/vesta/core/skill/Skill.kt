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
    /** 제안된 초안 (M28 K-11): 승인하기 전에는 부를 수 없다 */
    val draft: Boolean = false,
    /** 실행에서 자동으로 만든 것 (K-09) */
    val auto: Boolean = false,
    /** 만든 실행 ID (자동으로 만든 것) */
    val run: Long? = null,
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
            "web", "docs", "chat", "image", "video", "code", "task", "agent", "team", "skills", "agents", "start",
            "exit", "quit", "help", "new", "sessions", "resume", "history", "accept", "skillify",
        )

        /**
         * 웹 저장·초안 승인 때의 검사 (K-06·K-11): 이름 규칙·예약어, 길이, 숨은 문자, 그 역할이 쓸 수 있는 에이전트, 쓸 수 있는 도구만.
         * 문제가 있으면 그 이유, 없으면 null. TASK가 아니면 도구는 버린다
         */
        fun problem(name: String, description: String, body: String, agent: String, tools: List<String>, agents: Set<AgentType>, allowedTools: Set<String>): String? {
            if (!NAME.matches(name)) return "이름은 글자·숫자로 시작하는 1~32자(글자·숫자·_-)여야 합니다 (/이름 으로 부릅니다)"
            if (name.lowercase() in RESERVED) return "이미 쓰는 명령 이름입니다: /$name"
            if (description.isBlank() || description.length > MAX_DESCRIPTION) return "설명은 1~${MAX_DESCRIPTION}자입니다"
            if (body.isBlank() || body.length > MAX_BODY) return "지침은 1~${MAX_BODY}자입니다"
            if (dev.vesta.core.text.HiddenChars.has(name + description + body)) return "보이지 않는 문자가 들어 있습니다"
            val type = runCatching { AgentType.valueOf(agent) }.getOrNull()?.takeIf { it in AGENTS } ?: return "에이전트는 ${AGENTS.joinToString { it.name }} 중 하나입니다"
            if (type !in agents) return "이 계정은 ${type.name} 에이전트를 쓸 수 없습니다"
            val unknown = if (type == AgentType.TASK) tools.distinct().filter { it !in allowedTools } else emptyList()
            if (unknown.isNotEmpty()) return "쓸 수 없는 도구: ${unknown.joinToString()}"
            return null
        }

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

/** 웹에서 만든 스킬 (사용자별). [list]·[find]는 쓰는 것만, 초안(M28)은 [drafts] */
interface SkillRepository {
    suspend fun list(owner: UserId): List<Skill>
    suspend fun find(owner: UserId, name: String): Skill?
    /** 쓰는 스킬로 저장한다. 같은 이름의 초안이 있으면 그것을 바꾼다 (승인) */
    suspend fun save(owner: UserId, skill: Skill): Skill
    /** 초안도 지운다 */
    suspend fun delete(owner: UserId, name: String): Boolean
    /** 제안된 초안, 새것부터 */
    suspend fun drafts(owner: UserId): List<Skill> = emptyList()
    /** 초안으로 저장한다 ([skill]의 이름이 쓰는 스킬·초안과 겹치지 않아야 한다) */
    suspend fun saveDraft(owner: UserId, skill: Skill): Skill = error("초안을 저장할 수 없습니다")
    /** 스킬 제안이 어느 실행까지 봤는지 (K-08) */
    suspend fun cursor(): Long = 0L
    suspend fun setCursor(at: Long) {}
}

/** `/이름 할 일` (메신저는 `!이름`) */
data class SkillCall(val name: String, val arguments: String) {
    companion object {
        private val PATTERN = Regex("^[/!]([\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,31})(?:\\s+(.*))?$", RegexOption.DOT_MATCHES_ALL)
        fun parse(text: String): SkillCall? = PATTERN.find(text.trim())?.let { SkillCall(it.groupValues[1], it.groupValues[2].trim()) }
            ?.takeIf { it.name.lowercase() !in Skill.RESERVED }
    }
}
