package dev.vesta.core.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.UserContext
import kotlinx.serialization.json.JsonObject

/** 모델에게 보여 주는 도구 정의 (OpenAI function 형식). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** 모델이 요청한 도구 호출. [arguments]는 JSON 문자열. */
data class ToolCall(val id: String, val name: String, val arguments: String)

data class ToolResult(
    /** 모델에게 돌려줄 결과 (길면 잘라서) */
    val output: String,
    val isError: Boolean = false,
    /** 사용자에게 보여 줄 한 줄 요약 */
    val summary: String = output.lineSequence().firstOrNull().orEmpty().take(120),
)

/**
 * 도구 (PROJECT.md 7.7 T-01). 위험 등급에 따라 승인 정책이 적용된다:
 * READ 자동 / NETWORK 설정에 따름 / WRITE 승인 / EXEC 매번 승인.
 */
interface Tool {
    val spec: ToolSpec
    val risk: RiskLevel

    /**
     * 사용자의 로컬 데이터(파일·문서)를 결과로 돌려주는 도구인지.
     * 이런 도구가 한 번 실행된 뒤에는 NETWORK 도구도 승인을 받는다 — 파일 속 지시문(프롬프트 인젝션)이
     * 로컬 내용을 검색어·URL에 실어 밖으로 보내는 것을 막기 위해서다.
     */
    val readsLocalData: Boolean get() = false

    /** 승인 화면에 보여 줄 내용 (무엇을 하려는지 전부). READ 도구는 쓰지 않는다. */
    fun describe(arguments: JsonObject): ApprovalDetails {
        // 실행 로그용 한 줄: 첫 문자열 인자(대개 경로·검색어)를 보여 준다
        val first = arguments.values.firstNotNullOfOrNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return ApprovalDetails(listOfNotNull(spec.name, first?.take(100)).joinToString(" "), arguments.toString())
    }

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

data class ToolContext(val user: UserContext)

/** 승인 화면 내용 */
data class ApprovalDetails(
    /** 한 줄 요약. 예: "파일 쓰기: ~/Documents/summary.md (1.2KB, 새 파일)" */
    val title: String,
    /** 전체 내용. 셸이면 명령 전체·작업 디렉터리, 파일 쓰기면 내용 미리보기 */
    val body: String,
    /** D8 등 경고 */
    val warning: String? = null,
    /**
     * 이 작업이 쓰는 실제 경로들 (허용 폴더 검사를 통과한 절대 경로). 내장 파일 도구만 채운다.
     * CLI 쓰기 자동 승인(M17)이 "작업 폴더 안의 일반 파일"인지 볼 때 쓴다. 비어 있으면 자동 승인하지 않는다
     */
    val targets: List<String> = emptyList(),
)

sealed interface ApprovalDecision {
    data object Approve : ApprovalDecision
    data class Deny(val reason: String = "사용자가 거부함") : ApprovalDecision
    /** 셸 명령 수정 후 승인 (T-07) */
    data class ApproveEdited(val editedArguments: JsonObject) : ApprovalDecision
}

/** 열린 승인 요청 하나. 채널의 답이 [result]로 들어온다. */
class PendingApproval(val id: String, val owner: UserContext) {
    val result = kotlinx.coroutines.CompletableDeferred<ApprovalDecision>()
}

/**
 * 승인 요청 중계 (PROJECT.md 6.1 ApprovalBroker).
 * 에이전트가 [open]으로 요청을 열고 ApprovalRequested 이벤트를 내보낸 뒤 결과를 기다린다.
 * 채널(CLI·웹·메신저)은 사용자 답을 ConversationService.resolveApproval → [resolve]로 넘긴다.
 */
interface ApprovalBroker {
    fun open(owner: UserContext): PendingApproval
    /** 요청을 연 사용자 본인만 답할 수 있다 (6.6). */
    fun resolve(user: UserContext, id: String, decision: ApprovalDecision): Boolean
    fun close(id: String)
}

/** 도구 실행 기록 (PROJECT.md T-08, 11장 ToolRun). 셸 명령·승인 여부·결과를 남긴다. */
interface ToolRunLog {
    suspend fun record(
        user: UserContext,
        sessionId: dev.vesta.core.SessionId,
        tool: String,
        arguments: String,
        risk: dev.vesta.core.RiskLevel,
        /** 승인이 필요 없으면 null */
        approved: Boolean?,
        result: ToolResult,
        durationMs: Long,
    )
}
