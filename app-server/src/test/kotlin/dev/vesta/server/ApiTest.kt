package dev.vesta.server

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.RiskLevel
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.Session
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.session.StoredMessage
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import dev.vesta.app.PasswordHasher
import dev.vesta.storage.VestaDatabase
import dev.vesta.storage.WebAuthRepository
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import kotlin.io.path.createTempDirectory

private class FakeConversation : ConversationService {
    val decisions = mutableListOf<Pair<String, ApprovalDecision>>()
    var lastChannel: ChannelType? = null
    var lastInput: UserInput? = null
    override fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent> = flow {
        lastChannel = channel
        lastInput = input
        emit(AgentEvent.SessionReady(SessionId("s1"), "제목", true))
        if (input.text == "위험") emit(AgentEvent.ApprovalRequested("r1", "run_shell", RiskLevel.EXEC, ApprovalDetails("셸", "ls"), null))
        emit(AgentEvent.Token("안녕"))
        emit(AgentEvent.Token("하세요"))
        emit(AgentEvent.Done(AgentType.CHAT, "m", null))
    }
    override suspend fun resolveApproval(user: UserContext, requestId: String, decision: ApprovalDecision): Boolean {
        decisions += requestId to decision
        return true
    }
}

private object NoSessions : SessionRepository {
    override suspend fun create(userId: UserId, channel: ChannelType, title: String?) = error("x")
    override suspend fun find(userId: UserId, id: SessionId): Session? = null
    override suspend fun listRecent(userId: UserId, limit: Int) = listOf(Session(SessionId("s1"), userId, "제목", ChannelType.CLI, null, Instant.EPOCH, Instant.EPOCH))
    override suspend fun updateTitle(userId: UserId, id: SessionId, title: String) = Unit
}

private object NoMessages : MessageRepository {
    override suspend fun append(sessionId: SessionId, message: NewMessage): StoredMessage = error("x")
    override suspend fun list(sessionId: SessionId) = emptyList<StoredMessage>()
}

class ApiTest : FunSpec({
    val conversation = FakeConversation()
    val deps = ServerDeps(conversation, NoSessions, NoMessages, UserContext(UserId(1), UserRole.OWNER), token = "secret", version = "t")

    test("health는 토큰 없이, 나머지는 토큰 필요") { testApplication {
        application { vestaApi(deps) }
        client.get("/api/health").bodyAsText() shouldContain "\"status\":\"ok\""
        client.get("/api/sessions").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/sessions") { bearerAuth("wrong") }.status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/sessions") { bearerAuth("secret") }.bodyAsText() shouldContain "\"id\":\"s1\""
    } }

    test("/api/chat은 이벤트를 SSE로 흘려보낸다") { testApplication {
        application { vestaApi(deps) }
        val body = client.post("/api/chat") { bearerAuth("secret"); setBody("""{"text":"hi","channel":"CLI"}""") }.bodyAsText()
        body shouldContain "data: {\"type\":\"session\""
        body shouldContain "\"type\":\"token\",\"text\":\"안녕\""
        body shouldContain "\"type\":\"done\""
        conversation.lastChannel shouldBe ChannelType.CLI
    } }

    test("승인 응답을 대화 엔진에 넘긴다") { testApplication {
        application { vestaApi(deps) }
        client.post("/api/approvals/r9") { bearerAuth("secret"); setBody("""{"decision":"edit","edited":{"command":"ls -la"}}""") }.status shouldBe HttpStatusCode.OK
        (conversation.decisions.last().second as ApprovalDecision.ApproveEdited).editedArguments.toString() shouldContain "ls -la"
    } }

    test("OpenAI 호환: 답을 모아 돌려주고, 승인 요청은 자동 거부한다") { testApplication {
        application { vestaApi(deps) }
        val body = client.post("/v1/chat/completions") { bearerAuth("secret"); setBody("""{"model":"vesta","messages":[{"role":"user","content":"위험"}]}""") }.bodyAsText()
        body shouldContain "\"content\":\"안녕하세요\""
        (conversation.decisions.last().second is ApprovalDecision.Deny) shouldBe true
        val stream = client.post("/v1/chat/completions") { bearerAuth("secret"); setBody("""{"messages":[{"role":"user","content":"hi"}],"stream":true}""") }.bodyAsText()
        stream shouldContain "chat.completion.chunk"
        stream shouldContain "data: [DONE]"
        client.get("/v1/models") { bearerAuth("secret") }.bodyAsText() shouldContain "\"id\":\"vesta\""
    } }

    fun webDeps(): ServerDeps {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val repo = WebAuthRepository(db.db)
        kotlinx.coroutines.runBlocking { repo.setPasswordHash(owner, PasswordHasher.hash("correct horse")) }
        return ServerDeps(conversation, NoSessions, NoMessages, UserContext(owner, UserRole.OWNER), token = "secret", version = "t", auth = WebAuth(repo))
    }

    test("웹 로그인: 쿠키로 조회하고, POST는 CSRF 헤더가 있어야 한다") { testApplication {
        val deps = webDeps()
        application { vestaApi(deps) }
        client.post("/api/login") { setBody("""{"password":"wrong"}""") }.status shouldBe HttpStatusCode.Unauthorized
        val login = client.post("/api/login") { setBody("""{"password":"correct horse"}""") }
        login.status shouldBe HttpStatusCode.OK
        val setCookie = login.headers[io.ktor.http.HttpHeaders.SetCookie].orEmpty()
        setCookie shouldContain "HttpOnly"
        setCookie shouldContain "SameSite=Strict"
        val cookie = setCookie.substringBefore(';')
        val csrf = dev.vesta.api.ApiJson.decodeFromString(dev.vesta.api.MeDto.serializer(), login.bodyAsText()).csrf

        client.get("/api/sessions") { header("Cookie", cookie) }.status shouldBe HttpStatusCode.OK
        client.get("/api/me") { header("Cookie", cookie) }.bodyAsText() shouldContain csrf
        client.post("/api/chat") { header("Cookie", cookie); setBody("""{"text":"hi"}""") }.status shouldBe HttpStatusCode.Forbidden
        client.post("/api/chat") { header("Cookie", cookie); header("X-Vesta-CSRF", "x$csrf"); setBody("""{"text":"hi"}""") }.status shouldBe HttpStatusCode.Forbidden
        client.post("/api/chat") { header("Cookie", cookie); header("X-Vesta-CSRF", csrf); setBody("""{"text":"hi"}""") }.bodyAsText() shouldContain "\"type\":\"done\""
        conversation.lastChannel shouldBe ChannelType.WEB
        // OpenAI 호환 경로는 쿠키로 열 수 없다
        client.get("/v1/models") { header("Cookie", cookie) }.status shouldBe HttpStatusCode.Unauthorized

        client.post("/api/logout") { header("Cookie", cookie); header("X-Vesta-CSRF", csrf) }.status shouldBe HttpStatusCode.OK
        client.get("/api/sessions") { header("Cookie", cookie) }.status shouldBe HttpStatusCode.Unauthorized
    } }

    test("연속으로 틀리면 잠긴다") { testApplication {
        val deps = webDeps()
        application { vestaApi(deps) }
        repeat(5) { client.post("/api/login") { setBody("""{"password":"x"}""") } }
        client.post("/api/login") { setBody("""{"password":"correct horse"}""") }.status shouldBe HttpStatusCode.TooManyRequests
    } }

    test("동시에 보낸 로그인도 잠금 한도를 넘지 못한다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val repo = WebAuthRepository(db.db)
        repo.setPasswordHash(db.ensureOwner("owner"), PasswordHasher.hash("correct horse"))
        val auth = WebAuth(repo)
        val results = kotlinx.coroutines.coroutineScope {
            (1..20).map { async(kotlinx.coroutines.Dispatchers.Default) { auth.login(null, "wrong$it", null) } }.map { it.await() }
        }
        results.count { it == LoginResult.Invalid } shouldBe 5
        auth.login(null, "correct horse", null)::class shouldBe LoginResult.Locked::class
    }

    test("모르는 Host는 거부한다 (DNS 리바인딩 방지)") { testApplication {
        application { vestaApi(deps) }
        client.get("/api/health") { header("Host", "evil.example.com") }.status shouldBe HttpStatusCode.Forbidden
        client.get("/api/health") { header("Host", "127.0.0.1:8787") }.status shouldBe HttpStatusCode.OK
        client.get("/api/health") { header("Host", "mac.tail1234.ts.net") }.status shouldBe HttpStatusCode.OK
        client.get("/api/health").headers["Content-Security-Policy"].orEmpty() shouldContain "default-src 'self'"
    } }

    test("이미지 질문: 형식·개수·크기를 확인해 대화 엔진에 넘긴다") { testApplication {
        application { vestaApi(deps) }
        val png = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1))
        client.post("/api/chat") { bearerAuth("secret"); setBody("""{"text":"뭐야","images":["data:image/png;base64,$png"]}""") }.status shouldBe HttpStatusCode.OK
        conversation.lastInput!!.images.single().mime shouldBe "image/png"
        val html = java.util.Base64.getEncoder().encodeToString("<html><script>".toByteArray())
        client.post("/api/chat") { bearerAuth("secret"); setBody("""{"text":"x","images":["$html"]}""") }.let {
            it.status shouldBe HttpStatusCode.BadRequest
            it.bodyAsText() shouldContain "PNG"
        }
        client.post("/api/chat") { bearerAuth("secret"); setBody("""{"text":"x","images":["$png","$png","$png","$png","$png"]}""") }.status shouldBe HttpStatusCode.BadRequest
    } }

    test("음성 인식·읽어 주기·미디어: 설정이 없으면 503, 있으면 처리하고 미디어는 본인 것만") { testApplication {
        val owner = UserContext(UserId(1), UserRole.OWNER)
        val store = object : dev.vesta.core.media.MediaStore {
            override suspend fun save(userId: UserId, kind: dev.vesta.core.media.MediaKind, mime: String, bytes: ByteArray, caption: String?) = error("x")
            override suspend fun load(userId: UserId, id: String) =
                if (userId == owner.userId && id == "a1") dev.vesta.core.media.MediaAsset("a1", userId, dev.vesta.core.media.MediaKind.IMAGE, "image/png", 3) to byteArrayOf(1, 2, 3) else null
        }
        val full = ServerDeps(
            conversation, NoSessions, NoMessages, owner, token = "secret", version = "t",
            speechToText = object : dev.vesta.core.media.SpeechToText { override suspend fun transcribe(audio: ByteArray, language: String) = "음성 ${audio.size}" },
            textToSpeech = object : dev.vesta.core.media.TextToSpeech { override suspend fun synthesize(text: String, format: dev.vesta.core.media.AudioFormat) = "m4a".toByteArray() },
            media = store,
            mediaLimits = dev.vesta.core.config.MediaConfig(maxAudioMegabytes = 1),
        )
        application { vestaApi(full) }
        client.post("/api/stt") { bearerAuth("secret"); setBody(ByteArray(10)) }.bodyAsText() shouldBe """{"text":"음성 10"}"""
        client.post("/api/stt") { bearerAuth("secret"); setBody(ByteArray(2 * 1024 * 1024)) }.status shouldBe HttpStatusCode.PayloadTooLarge
        client.post("/api/speech") { bearerAuth("secret"); setBody("""{"text":"안녕"}""") }.let {
            it.status shouldBe HttpStatusCode.OK
            it.headers["Content-Type"] shouldBe "audio/mp4"
        }
        client.get("/api/media/a1") { bearerAuth("secret") }.headers["Content-Type"] shouldBe "image/png"
        client.get("/api/media/zz") { bearerAuth("secret") }.status shouldBe HttpStatusCode.NotFound
        client.get("/api/media/a1").status shouldBe HttpStatusCode.Unauthorized
    } }

    test("음성 설정이 없으면 503") { testApplication {
        application { vestaApi(deps) }
        client.post("/api/stt") { bearerAuth("secret"); setBody(ByteArray(10)) }.status shouldBe HttpStatusCode.ServiceUnavailable
    } }

})
