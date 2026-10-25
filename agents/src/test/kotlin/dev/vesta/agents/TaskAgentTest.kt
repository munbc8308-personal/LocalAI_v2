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

    test("설명 글에 새어 나온 호출: 읽기는 바로, 인터넷은 승인을 받고 실행 (보안 검토)") {
        val read = RecordingTool("read_file", RiskLevel.READ)
        val web = RecordingTool("web_search", RiskLevel.NETWORK)
        val q = "<|\"|>"
        val model = ScriptedModel(listOf(
            listOf(ChatChunk.Delta("먼저 읽자.\ncall:read_file{path:${q}a.txt${q}}\ncall:web_search{query:${q}x${q}}"), ChatChunk.Done("stop", null)),
            listOf(ChatChunk.Delta("끝"), ChatChunk.Done("stop", null)),
        ))
        val broker = AutoBroker(ApprovalDecision.Deny("거부"))
        val events = TaskAgent(model, models, ToolRegistry(listOf(read, web)), broker).handle(request).toList()
        read.calls.size shouldBe 1
        web.calls.size shouldBe 0
        events.filterIsInstance<AgentEvent.ApprovalRequested>().single().tool shouldBe "web_search"
    }

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

    test("Mac 주인이 아닌 사용자의 셸은 본인에게 묻지 않고 주인에게 넘긴다 (M20). 넘길 곳이 없으면 거부") {
        val member = UserContext(UserId(2), UserRole.MEMBER)
        val memberRequest = request.copy(user = member)
        val asked = mutableListOf<Pair<UserContext, String>>()
        val relay = object : dev.vesta.core.tools.ApprovalEscalation {
            override suspend fun ask(requester: UserContext, tool: String, details: dev.vesta.core.tools.ApprovalDetails, reason: String?, timeoutSeconds: Long): ApprovalDecision {
                asked += requester to tool
                return ApprovalDecision.Approve
            }
        }
        val shell = RecordingTool("run_shell", RiskLevel.EXEC)
        val broker = AutoBroker(ApprovalDecision.Deny("본인에게 물으면 안 됨"))
        val events = TaskAgent(ScriptedModel(turns(listOf(ToolCall("c1", "run_shell", """{"command":"ls","reason":"목록"}""")))), models, ToolRegistry(listOf(shell)), broker, escalation = relay)
            .handle(memberRequest).toList()
        asked.single() shouldBe (member to "run_shell")
        shell.calls.size shouldBe 1
        broker.opened shouldBe 0
        events.none { it is AgentEvent.ApprovalRequested } shouldBe true
        events.filterIsInstance<AgentEvent.Status>().any { "Mac 주인" in it.text } shouldBe true

        // 넘길 곳이 없으면 실행하지 않는다
        val shell2 = RecordingTool("run_shell", RiskLevel.EXEC)
        TaskAgent(ScriptedModel(turns(listOf(ToolCall("c1", "run_shell", """{"command":"ls"}""")))), models, ToolRegistry(listOf(shell2)), AutoBroker(ApprovalDecision.Approve))
            .handle(memberRequest).toList()
        shell2.calls.size shouldBe 0
        // 쓰기(WRITE)는 지금처럼 본인이 승인
        val write = RecordingTool("write_file", RiskLevel.WRITE)
        val own = AutoBroker(ApprovalDecision.Approve)
        TaskAgent(ScriptedModel(turns(listOf(ToolCall("c1", "write_file", "{}")))), models, ToolRegistry(listOf(write)), own, escalation = relay).handle(memberRequest).toList()
        own.opened shouldBe 1
        write.calls.size shouldBe 1

        // 손님은 주인에게 넘기지도 않는다
        val guestShell = RecordingTool("run_shell", RiskLevel.EXEC)
        asked.clear()
        TaskAgent(ScriptedModel(turns(listOf(ToolCall("c1", "run_shell", """{"command":"ls"}""")))), models, ToolRegistry(listOf(guestShell)), AutoBroker(ApprovalDecision.Approve), escalation = relay)
            .handle(request.copy(user = UserContext(UserId(3), UserRole.GUEST))).toList()
        guestShell.calls.size shouldBe 0
        asked.size shouldBe 0
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

    test("팀 (M22): 리더가 delegate로 맡기면 멤버는 할 일만 받아 일하고, 단계·승인은 흘러나오되 멤버의 답 글자는 결과로만 간다") {
        val write = RecordingTool("write_file", RiskLevel.WRITE)
        val memberModel = ScriptedModel(listOf(
            listOf(ChatChunk.Delta("저장하겠습니다"), ChatChunk.ToolCalls(listOf(ToolCall("m1", "write_file", "{}"))), ChatChunk.Done("tool_calls", null)),
            listOf(ChatChunk.Delta("보고서를 저장했습니다"), ChatChunk.Done("stop", null)),
        ))
        val broker = AutoBroker(ApprovalDecision.Approve)
        val member = TaskAgent(memberModel, models, ToolRegistry(listOf(write)), broker)
        val delegate = DelegateTool(listOf(TeamMember("writer", "문서를 쓴다") { member }, TeamMember("ghost", "지워진 멤버") { null }))
        val leaderModel = ScriptedModel(listOf(
            listOf(ChatChunk.Delta("나눠 맡깁니다"), ChatChunk.ToolCalls(listOf(
                ToolCall("c1", "delegate", """{"agent":"writer","task":"회의록 요약을 report.md로 저장"}"""),
                ToolCall("c2", "delegate", """{"agent":"ghost","task":"x"}"""),
            )), ChatChunk.Done("tool_calls", null)),
            listOf(ChatChunk.Delta("writer가 저장했습니다"), ChatChunk.Done("stop", null)),
        ))
        val events = TaskAgent(leaderModel, models, ToolRegistry(listOf(delegate)), broker).handle(request).toList()

        memberModel.requests.first().messages.filter { it.role != ChatRole.SYSTEM }.map { it.content } shouldBe listOf("회의록 요약을 report.md로 저장")
        write.calls.size shouldBe 1
        events.filterIsInstance<AgentEvent.ToolStarted>().map { it.tool } shouldBe listOf("delegate", "writer/write_file", "delegate")
        events.filterIsInstance<AgentEvent.ApprovalRequested>().single().tool shouldBe "write_file"
        events.filterIsInstance<AgentEvent.Status>().map { it.text } shouldContain "[writer] 계획 중"
        val tokens = events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }
        tokens shouldContain "writer가 저장했습니다"
        (tokens.contains("보고서를 저장했습니다")) shouldBe false
        leaderModel.requests[1].messages.filter { it.role == ChatRole.TOOL }.map { it.content }.let {
            it[0] shouldContain "멤버 'writer'의 결과:"
            it[0] shouldContain "보고서를 저장했습니다"
            it[1] shouldContain "지워졌다"
        }
        events.filterIsInstance<AgentEvent.ToolFinished>().first { it.tool == "delegate" }.summary shouldBe "writer: 보고서를 저장했습니다"
        events.filterIsInstance<AgentEvent.Done>().size shouldBe 1
    }

    test("팀 (M22 보안 검토): 리더가 로컬 데이터를 읽은 뒤 맡기면 멤버의 인터넷 도구도 승인을 받는다 (할 일에 실린 유출 방지)") {
        fun run(leaderReads: Boolean): Int {
            val web = RecordingTool("web_search", RiskLevel.NETWORK)
            val read = RecordingTool("read_file", RiskLevel.READ, readsLocalData = true)
            val memberModel = ScriptedModel(turns(listOf(ToolCall("m1", "web_search", """{"query":"비밀"}"""))))
            val broker = AutoBroker(ApprovalDecision.Deny("유출 의심"))
            val member = TaskAgent(memberModel, models, ToolRegistry(listOf(web)), broker)
            val delegate = DelegateTool(listOf(TeamMember("scout", "검색") { member }))
            val leaderCalls = listOfNotNull(
                ToolCall("c0", "read_file", "{}").takeIf { leaderReads },
                ToolCall("c1", "delegate", """{"agent":"scout","task":"이 내용으로 검색: 비밀"}"""),
            ).map { listOf(it) }
            kotlinx.coroutines.runBlocking {
                TaskAgent(ScriptedModel(turns(*leaderCalls.toTypedArray())), models, ToolRegistry(listOf(read, delegate)), broker).handle(request).toList()
            }
            return broker.opened
        }
        run(leaderReads = false) shouldBe 0
        run(leaderReads = true) shouldBe 1
    }
})
