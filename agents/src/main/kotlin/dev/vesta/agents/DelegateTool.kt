package dev.vesta.agents

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.tools.DelegatingTool
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 팀 멤버 하나: 이름·역할 설명, 맡길 때 만드는 에이전트 */
class TeamMember(val name: String, val description: String, val agent: suspend () -> Agent?)

/**
 * 나눠 맡기기 (A-14, M22). 팀 리더에게 주는 `delegate` 도구: 멤버에게 할 일을 주고 멤버의 답을 결과로 돌려준다.
 * - 멤버는 리더의 대화를 보지 못한다: 할 일(+맥락)만 받아 따로 맥락에서 일한다
 * - 멤버의 단계는 `멤버/도구`, `[멤버] 상태`로 바꿔 흘리고, 승인 요청은 그대로 흘린다 (실행을 시작한 사람이 승인)
 * - 멤버의 답 글자·생각·끝 알림은 흘리지 않는다 (리더의 답과 섞이지 않게). 답은 결과로만 돌려준다
 * - 멤버 결과에 로컬 데이터가 있을 수 있어 [readsLocalData]: 이후 리더의 인터넷 도구는 승인을 받고, 결과 속 지시문 검사(D10)를 거친다
 */
class DelegateTool(private val members: List<TeamMember>, private val maxResultChars: Int = 6_000) : DelegatingTool {
    override val risk = RiskLevel.READ
    override val readsLocalData = true

    override val spec = ToolSpec(
        NAME,
        "팀 멤버에게 일을 맡기고 그 결과를 받는다. 멤버는 이 대화를 보지 못하므로 task에 필요한 맥락(파일 경로·조건·앞 단계 결과)을 모두 적는다. 멤버: " +
            members.joinToString("; ") { "${it.name} — ${it.description}" },
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("agent") {
                    put("type", "string")
                    put("description", "맡을 멤버 이름")
                    putJsonArray("enum") { members.forEach { add(it.name) } }
                }
                putJsonObject("task") {
                    put("type", "string")
                    put("description", "멤버에게 줄 할 일과 맥락 전부")
                }
            }
            putJsonArray("required") { add("agent"); add("task") }
        },
    )

    override fun describe(arguments: JsonObject) = dev.vesta.core.tools.ApprovalDetails(
        "${arguments.text("agent")}에게: ${arguments.text("task").lineSequence().firstOrNull().orEmpty().take(100)}",
        arguments.text("task"),
    )

    override suspend fun delegate(arguments: JsonObject, request: AgentRequest, emit: suspend (AgentEvent) -> Unit): ToolResult {
        val name = arguments.text("agent")
        val task = arguments.text("task").trim()
        val member = members.firstOrNull { it.name == name } ?: return ToolResult("오류: 없는 멤버 '$name'. 멤버: ${members.joinToString { it.name }}", isError = true)
        if (task.isEmpty()) return ToolResult("오류: task가 비어 있다", isError = true)
        val agent = member.agent() ?: return ToolResult("오류: 멤버 '$name' 에이전트가 지워졌다", isError = true)
        val answer = StringBuilder()
        // 마지막 도구 뒤의 글 (요약 한 줄용: 앞쪽은 대개 "확인하겠습니다" 같은 계획)
        val last = StringBuilder()
        var failed: String? = null
        agent.handle(request.copy(history = listOf(ChatMessage(ChatRole.USER, task)), collections = emptyList())).collect { event ->
            when (event) {
                is AgentEvent.Token -> { answer.append(event.text); last.append(event.text) }
                is AgentEvent.Status -> emit(AgentEvent.Status("[$name] ${event.text}"))
                is AgentEvent.ToolStarted -> last.clear().let { emit(event.copy(callId = "$name:${event.callId}", tool = "$name/${event.tool}")) }
                is AgentEvent.ToolFinished -> emit(event.copy(callId = "$name:${event.callId}", tool = "$name/${event.tool}"))
                is AgentEvent.ApprovalRequested -> emit(event)
                is AgentEvent.Error -> { failed = event.message; emit(AgentEvent.Status("[$name] 오류: ${event.message}")) }
                else -> Unit
            }
        }
        val text = answer.toString().trim()
        failed?.let { if (text.isEmpty()) return ToolResult("멤버 '$name'이 실패했다: $it", isError = true, summary = "$name 실패") }
        if (text.isEmpty()) return ToolResult("멤버 '$name'이 답을 내지 않았다", isError = true, summary = "$name: 답 없음")
        val output = if (text.length > maxResultChars) text.take(maxResultChars) + "\n…(멤버 답이 길어 ${maxResultChars}자까지만)" else text
        return ToolResult("멤버 '$name'의 결과:\n$output", summary = "$name: " + last.toString().ifBlank { text }.lineSequence().first { it.isNotBlank() }.trim().take(100))
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()

    companion object {
        const val NAME = "delegate"
    }
}
