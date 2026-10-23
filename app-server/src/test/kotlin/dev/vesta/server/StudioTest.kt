package dev.vesta.server

import dev.vesta.app.StudioJournal
import dev.vesta.core.AgentType
import dev.vesta.core.RiskLevel
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingStage
import dev.vesta.core.studio.RunStatus
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import dev.vesta.storage.ExposedCustomAgentRepository
import dev.vesta.storage.ExposedRunRepository
import dev.vesta.storage.VestaDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory

private class NamedTool(name: String, override val risk: RiskLevel) : Tool {
    override val spec = ToolSpec(name, "$name 설명", JsonObject(emptyMap()))
    override suspend fun execute(arguments: JsonObject, context: ToolContext) = ToolResult("ok")
}

private object SilentConversation : dev.vesta.core.conversation.ConversationService {
    override fun send(user: UserContext, channel: dev.vesta.core.ChannelType, sessionId: dev.vesta.core.SessionId?, input: dev.vesta.core.agent.UserInput): Flow<AgentEvent> = emptyFlow()
    override suspend fun resolveApproval(user: UserContext, requestId: String, decision: dev.vesta.core.tools.ApprovalDecision) = false
}

private object EmptySessions : dev.vesta.core.session.SessionRepository {
    override suspend fun create(userId: UserId, channel: dev.vesta.core.ChannelType, title: String?) = error("x")
    override suspend fun find(userId: UserId, id: dev.vesta.core.SessionId): dev.vesta.core.session.Session? = null
    override suspend fun listRecent(userId: UserId, limit: Int) = emptyList<dev.vesta.core.session.Session>()
    override suspend fun updateTitle(userId: UserId, id: dev.vesta.core.SessionId, title: String) = Unit
}

private object EmptyMessages : dev.vesta.core.session.MessageRepository {
    override suspend fun append(sessionId: dev.vesta.core.SessionId, message: dev.vesta.core.session.NewMessage) = error("x")
    override suspend fun list(sessionId: dev.vesta.core.SessionId) = emptyList<dev.vesta.core.session.StoredMessage>()
}

class StudioTest : FunSpec({
    test("실행 기록 저널: 단계는 사람이 읽는 말로, 답 글자는 끝에 한 번, 소식은 주인 이름표를 달고 흐른다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val runs = ExposedRunRepository(db.db)
        val journal = StudioJournal(runs)
        val seen = mutableListOf<dev.vesta.app.RunUpdate>()
        kotlinx.coroutines.coroutineScope {
            val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { journal.updates.collect { seen += it } }
            val id = journal.start(owner, null, "/agent reviewer 봐 줘")
            journal.event(id, AgentEvent.Routed(RoutingDecision(AgentType.TASK, 1.0, RoutingStage.EXPLICIT, question = "봐 줘", customAgent = "reviewer")))
            journal.event(id, AgentEvent.Token("무시"))
            journal.event(id, AgentEvent.ToolStarted("c1", "read_file", RiskLevel.READ, "a.kt"))
            journal.event(id, AgentEvent.ToolFinished("c1", "read_file", true, "120줄"))
            journal.finish(id, RunStatus.DONE, "문제 없음")
            kotlinx.coroutines.yield()
            job.cancel()
            runs.get(id)!!.let { it.agent shouldBe "TASK"; it.customAgent shouldBe "reviewer"; it.status shouldBe RunStatus.DONE }
            runs.events(id).map { it.kind } shouldBe listOf("input", "routed", "tool_started", "tool_finished", "answer")
            runs.events(id)[1].title shouldContain "reviewer (사용자 정의 에이전트)"
            seen.all { it.user == owner } shouldBe true
            seen.last().finished?.status shouldBe RunStatus.DONE
        }
    }

    test("에이전트 탭 API: 쓸 수 있는 도구만, 이름 규칙, 실행은 자기 것만 본다") { testApplication {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = UserContext(db.ensureOwner("owner"), UserRole.OWNER)
        val agents = ExposedCustomAgentRepository(db.db)
        val runs = ExposedRunRepository(db.db)
        val studio = StudioEndpoints(agents, runs, emptyFlow(), { listOf(NamedTool("read_file", RiskLevel.READ), NamedTool("run_shell", RiskLevel.EXEC)) })
        val deps = ServerDeps(SilentConversation, EmptySessions, EmptyMessages, owner, token = "secret", version = "t", studio = studio)
        application { vestaApi(deps) }

        client.put("/api/agents/reviewer") { bearerAuth("secret"); setBody("""{"name":"","description":"코드 리뷰어","instructions":"짚기만","model":"CODE","tools":["read_file"]}""") }.status shouldBe HttpStatusCode.OK
        client.put("/api/agents/bad name") { bearerAuth("secret"); setBody("""{"name":"","description":"x","instructions":"","model":"GENERAL","tools":[]}""") }.status shouldBe HttpStatusCode.BadRequest
        client.put("/api/agents/sneaky") { bearerAuth("secret"); setBody("""{"name":"","description":"x","instructions":"","model":"GENERAL","tools":["write_file"]}""") }.let {
            it.status shouldBe HttpStatusCode.BadRequest
            it.bodyAsText() shouldContain "write_file"
        }
        client.put("/api/agents/big") { bearerAuth("secret"); setBody("""{"name":"","description":"x","instructions":"","model":"IMAGE","tools":[]}""") }.status shouldBe HttpStatusCode.BadRequest
        client.get("/api/agents") { bearerAuth("secret") }.bodyAsText().let {
            it shouldContain "\"name\":\"reviewer\""
            it shouldContain "\"run_shell\""
            it shouldNotContain "sneaky"
        }

        val mine = runs.start(owner.userId, null, "CHAT", null, "내 질문")
        val mina = dev.vesta.storage.ExposedUserRepository(db.db).create("mina", UserRole.MEMBER, null).id
        val other = runs.start(mina, null, "CHAT", null, "남의 질문")
        client.get("/api/runs") { bearerAuth("secret") }.bodyAsText().let { it shouldContain "내 질문"; it shouldNotContain "남의 질문" }
        client.get("/api/runs/${mine.id}") { bearerAuth("secret") }.status shouldBe HttpStatusCode.OK
        client.get("/api/runs/${other.id}") { bearerAuth("secret") }.status shouldBe HttpStatusCode.NotFound
        client.delete("/api/agents/reviewer") { bearerAuth("secret") }.status shouldBe HttpStatusCode.OK
        agents.list(owner.userId) shouldBe emptyList()
    } }

    test("팀 API: 리더·멤버는 내 에이전트, 리더는 멤버가 아니다. 나눠 맡긴 단계는 delegate 종류로 남는다") { testApplication {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = UserContext(db.ensureOwner("owner"), UserRole.OWNER)
        val agents = ExposedCustomAgentRepository(db.db)
        val teams = dev.vesta.storage.ExposedTeamRepository(db.db)
        val runs = ExposedRunRepository(db.db)
        listOf("planner", "coder").forEach { agents.save(dev.vesta.core.studio.CustomAgentDef(owner.userId, it, "$it 역할", "")) }
        val studio = StudioEndpoints(agents, runs, emptyFlow(), { emptyList() }, teams)
        application { vestaApi(ServerDeps(SilentConversation, EmptySessions, EmptyMessages, owner, token = "secret", version = "t", studio = studio)) }

        client.put("/api/teams/dev") { bearerAuth("secret"); setBody("""{"name":"","description":"기획 → 개발","leader":"planner","members":["coder"]}""") }.status shouldBe HttpStatusCode.OK
        client.put("/api/teams/self") { bearerAuth("secret"); setBody("""{"name":"","description":"x","leader":"planner","members":["planner"]}""") }.status shouldBe HttpStatusCode.BadRequest
        client.put("/api/teams/ghost") { bearerAuth("secret"); setBody("""{"name":"","description":"x","leader":"planner","members":["nobody"]}""") }.bodyAsText() shouldContain "nobody"
        client.put("/api/teams/empty") { bearerAuth("secret"); setBody("""{"name":"","description":"x","leader":"planner","members":[]}""") }.status shouldBe HttpStatusCode.BadRequest
        client.get("/api/agents") { bearerAuth("secret") }.bodyAsText().let { it shouldContain "\"leader\":\"planner\""; it shouldNotContain "ghost" }

        val journal = StudioJournal(runs)
        val id = journal.start(owner.userId, null, "/team dev 해 줘")
        journal.event(id, AgentEvent.Routed(RoutingDecision(AgentType.TASK, 1.0, RoutingStage.EXPLICIT, question = "해 줘", customAgent = "dev", team = true)))
        journal.event(id, AgentEvent.ToolStarted("c1", "delegate", RiskLevel.READ, "coder에게: 고쳐"))
        journal.event(id, AgentEvent.ToolStarted("coder:m1", "coder/read_file", RiskLevel.READ, "a.kt"))
        journal.event(id, AgentEvent.ToolFinished("c1", "delegate", true, "coder: 고쳤음"))
        journal.finish(id, RunStatus.DONE, "끝")
        runs.get(id)!!.agent shouldBe "TEAM"
        runs.events(id).map { it.kind } shouldBe listOf("input", "routed", "delegate_started", "tool_started", "delegate_finished", "answer")
        runs.events(id)[1].title shouldContain "dev (팀)"

        client.delete("/api/teams/dev") { bearerAuth("secret") }.status shouldBe HttpStatusCode.OK
        teams.list(owner.userId) shouldBe emptyList()
    } }

    test("A2A API: 카드 미리 보기 경고, 이름 겹침 거부, 팀 멤버로는 되고 리더로는 안 된다") { testApplication {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = UserContext(db.ensureOwner("owner"), UserRole.OWNER)
        val agents = ExposedCustomAgentRepository(db.db)
        val teams = dev.vesta.storage.ExposedTeamRepository(db.db)
        val remote = dev.vesta.storage.ExposedA2aAgentRepository(db.db)
        agents.save(dev.vesta.core.studio.CustomAgentDef(owner.userId, "lead", "리더", ""))
        val card = """{"name":"Echo Agent","description":"따라 말하기","supportedInterfaces":[{"url":"http://other.example/a2a","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],"securitySchemes":{"b":{}}}"""
        val client = dev.vesta.a2a.A2aClient(io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            if (request.url.host == "agent.example") respond(card, HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
            else respond("", HttpStatusCode.NotFound)
        }) { install(io.ktor.client.plugins.HttpTimeout) })
        val studio = StudioEndpoints(agents, ExposedRunRepository(db.db), emptyFlow(), { emptyList() }, teams, remote, client)
        application { vestaApi(ServerDeps(SilentConversation, EmptySessions, EmptyMessages, owner, token = "secret", version = "t", studio = studio)) }

        val preview = this.client.post("/api/a2a/preview") { bearerAuth("secret"); setBody("""{"url":"https://agent.example"}""") }.bodyAsText()
        preview shouldContain "\"suggestedName\":\"echo-agent\""
        preview shouldContain "호스트가 다릅니다"
        preview shouldContain "암호화되지 않은 http"
        preview shouldContain "dev.vesta.a2a"
        this.client.post("/api/a2a/preview") { bearerAuth("secret"); setBody("""{"url":"https://nothing.example"}""") }.status shouldBe HttpStatusCode.BadRequest
        this.client.put("/api/a2a/lead") { bearerAuth("secret"); setBody("""{"url":"https://agent.example","endpoint":"http://other.example/a2a"}""") }.bodyAsText() shouldContain "같은 이름"
        // 미리 보기에서 본 연결 주소와 다르면(그사이 카드가 바뀜) 거부
        this.client.put("/api/a2a/echo") { bearerAuth("secret"); setBody("""{"url":"https://agent.example","endpoint":"https://agent.example/a2a"}""") }.bodyAsText() shouldContain "바뀌었습니다"
        this.client.put("/api/a2a/echo") { bearerAuth("secret"); setBody("""{"url":"https://agent.example"}""") }.status shouldBe HttpStatusCode.BadRequest
        this.client.put("/api/a2a/echo") { bearerAuth("secret"); setBody("""{"url":"https://agent.example","endpoint":"http://other.example/a2a"}""") }.status shouldBe HttpStatusCode.OK
        remote.find(owner.userId, "echo")!!.let { it.endpoint shouldBe "http://other.example/a2a"; it.auth shouldBe true; it.protocol shouldBe "1.0" }
        this.client.put("/api/agents/echo") { bearerAuth("secret"); setBody("""{"name":"","description":"x","instructions":"","model":"GENERAL","tools":[]}""") }.bodyAsText() shouldContain "A2A"
        this.client.get("/api/agents") { bearerAuth("secret") }.bodyAsText().let { it shouldContain "\"canA2a\":true"; it shouldContain "\"displayName\":\"Echo Agent\"" }

        this.client.put("/api/teams/mix") { bearerAuth("secret"); setBody("""{"name":"","description":"섞인 팀","leader":"lead","members":["echo"]}""") }.status shouldBe HttpStatusCode.OK
        this.client.put("/api/teams/bad") { bearerAuth("secret"); setBody("""{"name":"","description":"x","leader":"echo","members":["lead"]}""") }.bodyAsText() shouldContain "리더는 사용자 정의 에이전트"
        this.client.delete("/api/a2a/echo") { bearerAuth("secret") }.status shouldBe HttpStatusCode.OK
    } }

    test("스킬 API: 예약어·숨은 문자·쓸 수 없는 도구 거부, 작업 스킬만 도구를 남긴다") { testApplication {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = UserContext(db.ensureOwner("owner"), UserRole.OWNER)
        val skills = dev.vesta.storage.ExposedSkillRepository(db.db)
        val studio = StudioEndpoints(ExposedCustomAgentRepository(db.db), ExposedRunRepository(db.db), emptyFlow(), { listOf(NamedTool("read_file", RiskLevel.READ)) }, skillRepo = skills)
        application { vestaApi(ServerDeps(SilentConversation, EmptySessions, EmptyMessages, owner, token = "secret", version = "t", studio = studio)) }
        client.put("/api/skills/fix") { bearerAuth("secret"); setBody("""{"name":"","description":"고치기","body":"고친다 ${'$'}ARGUMENTS","agent":"TASK","tools":["read_file"]}""") }.status shouldBe HttpStatusCode.OK
        client.put("/api/skills/sum") { bearerAuth("secret"); setBody("""{"name":"","description":"요약","body":"요약","agent":"CHAT","tools":["read_file"]}""") }.status shouldBe HttpStatusCode.OK
        skills.find(owner.userId, "sum")!!.tools shouldBe emptyList()
        client.put("/api/skills/web") { bearerAuth("secret"); setBody("""{"name":"","description":"x","body":"x"}""") }.bodyAsText() shouldContain "이미 쓰는 명령"
        client.put("/api/skills/bad") { bearerAuth("secret"); setBody("""{"name":"","description":"x","body":"x","agent":"TASK","tools":["run_shell"]}""") }.bodyAsText() shouldContain "run_shell"
        client.put("/api/skills/hid") { bearerAuth("secret"); setBody("{\"name\":\"\",\"description\":\"x\",\"body\":\"요약\u202e\"}") }.bodyAsText() shouldContain "보이지 않는"
        client.get("/api/agents") { bearerAuth("secret") }.bodyAsText() shouldContain "\"name\":\"fix\""
        client.delete("/api/skills/fix") { bearerAuth("secret") }.status shouldBe HttpStatusCode.OK
    } }
})
