package dev.vesta.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.agent.ClientToolSpec
import dev.vesta.core.tools.ApprovalDetails
import dev.vesta.core.tools.ClientToolBroker
import dev.vesta.core.tools.EmittingTool
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * 다른 PC의 CLI가 실행하는 도구 (RC-07). 모델에게는 보통 도구처럼 보이고, 실행은 [ClientToolBroker]로 CLI에 맡긴 뒤 결과를 기다린다.
 * - 이름은 [RISK]에 있는 것만, 위험 등급도 서버가 정한다 (CLI가 보낸 설명·인자 형식만 쓴다)
 * - 승인은 지금처럼 서버가 묻고(그 PC의 터미널에 뜬다), CLI는 승인한 인자와 같은 실행만 한다
 * - CLI 쪽도 작업 폴더 밖·비밀 파일·차단 명령을 스스로 막는다 (서버를 믿지 않는다)
 */
class ClientTool(
    clientSpec: ClientToolSpec,
    private val broker: ClientToolBroker,
    private val os: String?,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : EmittingTool {
    override val spec = ToolSpec(clientSpec.name, clientSpec.description.take(MAX_DESCRIPTION), Json.parseToJsonElement(clientSpec.parameters).jsonObject)
    override val risk: RiskLevel = RISK.getValue(clientSpec.name)
    // 파일 내용을 돌려주는 도구: 한 번 쓰면 인터넷 도구도 승인 (프롬프트 인젝션으로 밖에 내보내지 않게)
    override val readsLocalData = risk == RiskLevel.READ

    private val where = "내 PC" + (os?.let { " · $it" } ?: "")

    override fun describe(arguments: JsonObject): ApprovalDetails {
        fun arg(name: String) = (arguments[name] as? JsonPrimitive)?.content
        return when (spec.name) {
            "run_shell" -> {
                val command = arg("command").orEmpty()
                ApprovalDetails(
                    title = "셸 실행 ($where): $command",
                    body = "명령: $command\n작업 폴더: ${arg("cwd")}\n이유: ${arg("reason") ?: "(없음)"}\n실행 위치: 그 PC (격리 없음)",
                    warning = ShellTool.blocked(command)?.let { "차단 목록에 걸림 — 승인해도 실행하지 않음: $it" }
                        ?: if (ShellTool.dangerous(command)) "파일 삭제·덮어쓰기가 포함된 명령입니다" else null,
                )
            }
            "write_file" -> {
                val content = arg("content").orEmpty()
                ApprovalDetails("파일 쓰기 ($where): ${arg("path")} (${content.length}자, ${arg("mode") ?: "create"})", content.take(PREVIEW), targets = listOfNotNull(arg("path")))
            }
            "make_directory" -> ApprovalDetails("폴더 만들기 ($where): ${arg("path")}", "", targets = listOfNotNull(arg("path")))
            "move_file" -> ApprovalDetails("옮기기 ($where): ${arg("from")} → ${arg("to")}", "", targets = listOfNotNull(arg("from"), arg("to")))
            "apply_patch" -> ApprovalDetails("패치 적용 ($where): ${arg("repo")}", arg("patch").orEmpty().take(PREVIEW))
            else -> ApprovalDetails(listOfNotNull(spec.name, arguments.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content }?.take(100)).joinToString(" "), arguments.toString())
        }
    }

    override suspend fun run(arguments: JsonObject, request: AgentRequest, emit: suspend (AgentEvent) -> Unit): ToolResult {
        val pending = broker.open(request.user)
        try {
            emit(AgentEvent.ClientToolRequested(pending.id, spec.name, arguments.toString()))
            val result = withTimeoutOrNull(timeoutSeconds.seconds) { pending.result.await() }
                ?: return ToolResult("오류: $where 의 CLI가 ${timeoutSeconds}초 안에 결과를 돌려주지 않았다", isError = true)
            return result.copy(output = result.output.take(MAX_OUTPUT), summary = result.summary.take(200))
        } finally {
            broker.close(pending.id)
        }
    }

    companion object {
        /** CLI에 맡길 수 있는 도구와 위험 등급 (이 Mac의 같은 이름 도구와 같다) */
        val RISK = mapOf(
            "list_files" to RiskLevel.READ, "read_file" to RiskLevel.READ, "search_files" to RiskLevel.READ,
            "write_file" to RiskLevel.WRITE, "make_directory" to RiskLevel.WRITE, "move_file" to RiskLevel.WRITE, "apply_patch" to RiskLevel.WRITE,
            "run_shell" to RiskLevel.EXEC,
        )
        /** 빌드·테스트가 오래 걸려서 넉넉히 (CLI 셸 시간 초과보다 길게) */
        const val DEFAULT_TIMEOUT_SECONDS = 15 * 60L
        const val MAX_OUTPUT = 60_000
        private const val MAX_DESCRIPTION = 2_000
        private const val PREVIEW = 3_000

        /** CLI가 보낸 도구 목록에서 쓸 것만 (모르는 이름·깨진 형식은 버린다) */
        fun from(specs: List<ClientToolSpec>, broker: ClientToolBroker, os: String?): List<ClientTool> =
            specs.distinctBy { it.name }.filter { it.name in RISK }.mapNotNull { runCatching { ClientTool(it, broker, os) }.getOrNull() }
    }
}
