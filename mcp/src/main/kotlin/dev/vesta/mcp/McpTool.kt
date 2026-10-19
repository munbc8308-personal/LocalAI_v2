package dev.vesta.mcp

import dev.vesta.core.RiskLevel
import dev.vesta.core.tools.ApprovalDetails
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MCP 도구를 Vesta 도구로 감싼 것 (PROJECT.md 7.7a).
 * 위험 등급은 [RiskMapping]으로 정하고, MCP 서버의 결과는 개인 데이터(메일·메시지 등)이므로 [readsLocalData]로 본다.
 */
class McpTool(
    val server: String,
    val info: McpToolInfo,
    override val risk: RiskLevel,
    private val client: McpStdioClient,
    private val maxOutputChars: Int = 12_000,
) : Tool {
    override val spec = ToolSpec(info.name, info.description, info.inputSchema)
    override val readsLocalData = true

    /** calendar, reminder, mail … (D11 선택지). 이름 앞부분으로 나눈다. */
    val group: String = info.name.substringBefore('_')

    override fun describe(arguments: JsonObject): ApprovalDetails {
        val lines = arguments.entries.joinToString("\n") { (key, value) -> "$key: ${display(value)}" }
        val title = when (info.name) {
            "mail_send" -> "메일 보내기 → ${display(arguments["to"])} · 제목 \"${display(arguments["subject"])}\""
            "messages_send" -> "메시지 보내기 → ${display(arguments["recipient"] ?: arguments["to"] ?: arguments["chat_id"])}"
            "shortcuts_run" -> "단축어 실행: ${display(arguments["name"] ?: arguments["identifier"])}"
            else -> "${info.name} ${arguments.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.take(80).orEmpty()}".trim()
        }
        return ApprovalDetails(
            title = "[$server] $title",
            body = lines,
            warning = when {
                risk == RiskLevel.EXEC && info.name.endsWith("_send") -> "다른 사람에게 실제로 보내집니다. 받는 사람과 내용을 확인하세요."
                info.destructiveHint == true -> "삭제·덮어쓰기가 일어날 수 있습니다"
                else -> null
            },
        )
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val result = client.callTool(info.name, arguments)
        val text = result.text.ifBlank { if (result.isError) "오류" else "완료" }
        return ToolResult(
            output = text.take(maxOutputChars) + if (text.length > maxOutputChars) "\n… (잘림)" else "",
            isError = result.isError,
            summary = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120),
        )
    }

    private fun display(value: JsonElement?): String = when (value) {
        null -> "-"
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { display(it) }
        is JsonObject -> value.toString()
    }
}

/**
 * MCP 도구 위험 등급 (PROJECT.md 7.7a 위험 등급 매핑).
 * 1 설정 toolOverrides → 2 남에게 보내기·임의 실행(EXEC 고정) → 3 annotations(readOnlyHint=true → READ, 그 밖 WRITE) → 4 없으면 WRITE.
 * annotations는 서버가 주는 힌트일 뿐이라 READ 판정에만 쓴다.
 */
object RiskMapping {
    private val alwaysExec = setOf("mail_send", "messages_send", "shortcuts_run")

    fun of(info: McpToolInfo, overrides: Map<String, String>): RiskLevel {
        overrides[info.name]?.let { value -> runCatching { RiskLevel.valueOf(value.uppercase()) }.getOrNull()?.let { return it } }
        if (info.name in alwaysExec) return RiskLevel.EXEC
        return if (info.readOnlyHint == true) RiskLevel.READ else RiskLevel.WRITE
    }
}
