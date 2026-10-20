package dev.vesta.agents

import dev.vesta.core.ChannelType
import dev.vesta.core.ModelRole
import dev.vesta.core.RiskLevel
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.inference.ModelInfo
import dev.vesta.core.model.ModelProvider
import dev.vesta.core.session.Session
import dev.vesta.core.tools.ApprovalBroker
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.PendingApproval
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolCall
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import dev.vesta.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

private class ScriptedModel(private val turns: List<List<ChatChunk>>) : InferenceClient {
    val requests = mutableListOf<ChatRequest>()
    override suspend fun listModels() = emptyList<ModelInfo>()
    override suspend fun embed(model: String, texts: List<String>) = emptyList<FloatArray>()
    override fun chat(request: ChatRequest): Flow<ChatChunk> {
        requests += request
        return flowOf(*turns[requests.size - 1].toTypedArray())
    }
}

private class RecordingTool(private val toolName: String, override val risk: RiskLevel, override val readsLocalData: Boolean = false) : Tool {
    val calls = mutableListOf<JsonObject>()
    override val spec = ToolSpec(toolName, toolName, buildJsonObject { })
    override suspend fun execute(arguments: JsonObject, context: ToolContext) = ToolResult("$toolName 결과").also { calls += arguments }
}

/** 요청이 열리자마자 정해 둔 답을 넣는다 (채널이 바로 답한 것처럼) */
private class AutoBroker(private val decision: ApprovalDecision) : ApprovalBroker {
    var opened = 0
    override fun open(owner: UserContext) = PendingApproval("r${++opened}", owner).also { it.result.complete(decision) }
    override fun resolve(user: UserContext, id: String, decision: ApprovalDecision) = false
    override fun close(id: String) = Unit
}

class TaskAgentTest : FunSpec({
    val user = UserContext(UserId(1), UserRole.OWNER)
    val session = Session(SessionId("s"), user.userId, null, ChannelType.CLI, null, Instant.EPOCH, Instant.EPOCH)
    val models = object : ModelProvider { override suspend fun acquire(role: ModelRole) = ModelSpec("m") }
    val request = AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "회의록 찾아서 요약 저장해 줘")))

    fun turns(vararg calls: List<ToolCall>) = calls.map { listOf(ChatChunk.Delta("계획"), ChatChunk.ToolCalls(it), ChatChunk.Done("tool_calls", null)) } +
        listOf(listOf(ChatChunk.Delta("완료했습니다"), ChatChunk.Done("stop", null)))

    test("읽기 도구는 승인 없이, 쓰기 도구는 승인 후 실행하고 결과를 모델에 돌려준다") {
        val read = RecordingTool("read_file", RiskLevel.READ)
        val write = RecordingTool("write_file", RiskLevel.WRITE)
        val model = ScriptedModel(turns(listOf(ToolCall("c1", "read_file", "{}")), listOf(ToolCall("c2", "write_file", "{}"))))
        val broker = AutoBroker(ApprovalDecision.Approve)
        val events = TaskAgent(model, models, ToolRegistry(listOf(read, write)), broker).handle(request).toList()

        read.calls.size shouldBe 1
        write.calls.size shouldBe 1
        broker.opened shouldBe 1 // 쓰기 도구만 승인 요청
        events.filterIsInstance<AgentEvent.ApprovalRequested>().single().tool shouldBe "write_file"
        model.requests[1].messages.last().let { it.role shouldBe ChatRole.TOOL; it.content shouldBe "read_file 결과"; it.toolCallId shouldBe "c1" }
        model.requests.first().tools.map { it.name } shouldBe listOf("read_file", "write_file")
        (events.last() as AgentEvent.Done).agent shouldBe dev.vesta.core.AgentType.TASK
    }

    test("로컬 파일을 읽은 뒤에는 웹 도구도 승인을 받는다 (인젝션으로 인한 유출 방지)") {
        val read = RecordingTool("read_file", RiskLevel.READ, readsLocalData = true)
        val web = RecordingTool("web_search", RiskLevel.NETWORK)
        val before = AutoBroker(ApprovalDecision.Approve)
        TaskAgent(ScriptedModel(turns(listOf(ToolCall("c1", "web_search", "{}")))), models, ToolRegistry(listOf(read, web)), before).handle(request).toList()
        before.opened shouldBe 0 // 로컬 데이터를 읽기 전 웹 검색은 자동

        val after = AutoBroker(ApprovalDecision.Deny("유출 의심"))
        TaskAgent(
            ScriptedModel(turns(listOf(ToolCall("c1", "read_file", "{}")), listOf(ToolCall("c2", "web_search", """{"query":"비밀"}""")))),
            models, ToolRegistry(listOf(read, web)), after,
        ).handle(request).toList()
        after.opened shouldBe 1
        web.calls.size shouldBe 1 // 첫 테스트에서 1번, 두 번째는 거부되어 실행 안 됨
    }

    test("거부하면 실행하지 않고 거부 사실을 모델에 알린다") {
        val shell = RecordingTool("run_shell", RiskLevel.EXEC)
        val model = ScriptedModel(turns(listOf(ToolCall("c1", "run_shell", """{"command":"ls"}"""))))
        TaskAgent(model, models, ToolRegistry(listOf(shell)), AutoBroker(ApprovalDecision.Deny("싫음"))).handle(request).toList()
        shell.calls.size shouldBe 0
        model.requests[1].messages.last().content shouldContain "실행되지 않았다"
    }

    test("거부된 작업은 모델 답변과 상관없이 마지막에 시스템이 표시한다") {
        val send = RecordingTool("mail_send", RiskLevel.EXEC)
        val model = ScriptedModel(listOf(
            listOf(ChatChunk.ToolCalls(listOf(ToolCall("c1", "mail_send", "{}"))), ChatChunk.Done("tool_calls", null)),
            listOf(ChatChunk.Delta("메일을 보냈습니다."), ChatChunk.Done("stop", null)), // 모델이 거짓으로 말하는 경우
        ))
        val events = TaskAgent(model, models, ToolRegistry(listOf(send)), AutoBroker(ApprovalDecision.Deny("싫음"))).handle(request).toList()
        send.calls.size shouldBe 0
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text } shouldContain "실행하지 않은 작업"
    }

    test("셸은 수정 후 승인한 명령으로 실행한다") {
        val shell = RecordingTool("run_shell", RiskLevel.EXEC)
        val model = ScriptedModel(turns(listOf(ToolCall("c1", "run_shell", """{"command":"rm -r old"}"""))))
        val edited = buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive("ls old")) }
        TaskAgent(model, models, ToolRegistry(listOf(shell)), AutoBroker(ApprovalDecision.ApproveEdited(edited))).handle(request).toList()
        shell.calls.single() shouldBe edited
        // 모델은 원래 요청한 명령만 알고 있으므로 고친 명령으로 실행했다고 알려 준다
        model.requests[1].messages.last().content shouldContain "인자를 고쳤다"
        model.requests[1].messages.last().content shouldContain "ls old"
    }

    test("없는 도구·잘못된 인자는 오류로 모델에 돌려준다") {
        val model = ScriptedModel(turns(listOf(ToolCall("c1", "nope", "{}"), ToolCall("c2", "read_file", "not json"))))
        val events = TaskAgent(model, models, ToolRegistry(listOf(RecordingTool("read_file", RiskLevel.READ))), AutoBroker(ApprovalDecision.Approve))
            .handle(request).toList()
        model.requests[1].messages.takeLast(2).map { it.content.take(9) } shouldContain "오류: 없는 도구"
        events.last().javaClass.simpleName shouldBe "Done"
    }

    test("최대 단계에서 멈춘다") {
        val call = listOf(ToolCall("c", "read_file", "{}"))
        val model = ScriptedModel(List(5) { listOf(ChatChunk.ToolCalls(call), ChatChunk.Done("tool_calls", null)) })
        val events = TaskAgent(model, models, ToolRegistry(listOf(RecordingTool("read_file", RiskLevel.READ))), AutoBroker(ApprovalDecision.Approve), maxSteps = 3)
            .handle(request).toList()
        model.requests.size shouldBe 3
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text } shouldContain "3단계"
    }
})
