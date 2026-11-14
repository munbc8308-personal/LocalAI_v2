package dev.vesta.cli

import dev.vesta.api.ClientToolSpecDto
import dev.vesta.core.RiskLevel
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.tools.ApplyPatchTool
import dev.vesta.tools.ListFilesTool
import dev.vesta.tools.MakeDirectoryTool
import dev.vesta.tools.MoveFileTool
import dev.vesta.tools.PathSandbox
import dev.vesta.tools.ReadFileTool
import dev.vesta.tools.SearchFilesTool
import dev.vesta.tools.ShellTool
import dev.vesta.tools.WriteFileTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 다른 PC에서 도구 실행 (RC-07). `vesta login`으로 붙은 CLI가 서버의 실행 요청을 받아 이 PC의 작업 폴더에서 돌린다.
 * 서버를 믿지 않는다:
 * - 파일 도구는 작업 폴더 안만(비밀 파일·보호 폴더 제외), 셸은 차단 목록을 이 PC에서 다시 본다
 * - 쓰기·셸은 이 터미널에서 승인한(자동 승인 포함) 인자와 똑같은 요청만 한 번 실행한다. 승인 없이 온 요청은 실행하지 않는다
 */
internal class ClientWorkspace(val root: String, shellTimeoutSeconds: Long = SHELL_TIMEOUT_SECONDS) {
    val os: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}".trim()
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val sandbox = PathSandbox(listOf(root))
    private val tools: Map<String, Tool> = listOf(
        ListFilesTool(sandbox), ReadFileTool(sandbox, MAX_OUTPUT), SearchFilesTool(sandbox),
        WriteFileTool(sandbox), MakeDirectoryTool(sandbox), MoveFileTool(sandbox), ApplyPatchTool(sandbox),
        ShellTool(sandbox, shellTimeoutSeconds, MAX_OUTPUT, windows = windows),
    ).associateBy { it.spec.name }
    /** 이 터미널에서 승인했지만 아직 실행 요청이 오지 않은 것: (도구, 인자) */
    private val approved = mutableListOf<Pair<String, JsonObject>>()
    /** 승인 화면에 보여 준 요청 (요청 ID → 이벤트) */
    private val shown = mutableMapOf<String, AgentEvent.ApprovalRequested>()

    fun specs(): List<ClientToolSpecDto> = tools.values.map { ClientToolSpecDto(it.spec.name, it.spec.description, it.spec.parameters.toString()) }

    fun risk(name: String): RiskLevel? = tools[name]?.risk

    fun seen(event: AgentEvent.ApprovalRequested) { shown[event.requestId] = event }

    /** 이 터미널의 승인 결과. 승인이면 실제로 실행될 인자(수정본 반영)를 기억한다 */
    @Synchronized
    fun decided(requestId: String, decision: ApprovalDecision) {
        val event = shown.remove(requestId) ?: return
        val arguments = event.arguments?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return
        when (decision) {
            ApprovalDecision.Approve -> approved += event.tool to arguments
            is ApprovalDecision.ApproveEdited -> approved += event.tool to JsonObject(arguments + decision.editedArguments)
            is ApprovalDecision.Deny -> Unit
        }
    }

    /** 서버가 맡긴 실행 하나 */
    suspend fun run(tool: String, rawArguments: String): ToolResult {
        val target = tools[tool] ?: return ToolResult("오류: 이 PC에서 실행할 수 없는 도구 $tool", isError = true)
        val arguments = runCatching { Json.parseToJsonElement(rawArguments).jsonObject }.getOrElse { return ToolResult("오류: 인자 JSON을 읽지 못함", isError = true) }
        if (target.risk != RiskLevel.READ && !consume(tool, arguments)) {
            return ToolResult("오류: 이 PC의 CLI가 실행하지 않았다. 이 터미널에서 같은 인자로 승인한 요청이 아니다.", isError = true, summary = REFUSED)
        }
        return runCatching { target.execute(arguments, CONTEXT) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            ToolResult("오류: ${it.message ?: it::class.simpleName}", isError = true)
        }
    }

    @Synchronized
    private fun consume(tool: String, arguments: JsonObject): Boolean {
        val index = approved.indexOfFirst { it.first == tool && it.second == arguments }
        if (index < 0) return false
        approved.removeAt(index)
        return true
    }

    companion object {
        /** 빌드·테스트(Gradle·npm)가 오래 걸려 이 Mac의 셸(60초)보다 길게 */
        const val SHELL_TIMEOUT_SECONDS = 600L
        const val MAX_OUTPUT = 30_000
        const val REFUSED = "승인되지 않은 실행 요청이라 이 PC에서 하지 않음"
        private val CONTEXT = ToolContext(UserContext(UserId(0), UserRole.OWNER))
    }
}
