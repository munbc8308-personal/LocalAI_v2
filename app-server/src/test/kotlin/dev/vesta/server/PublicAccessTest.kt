package dev.vesta.server

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
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
import dev.vesta.identity.PasswordHasher
import dev.vesta.identity.UserService
import dev.vesta.storage.ExposedUserRepository
import dev.vesta.storage.VestaDatabase
import dev.vesta.storage.WebAuthRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.io.path.createTempDirectory

private const val TEAM = "family.cloudflareaccess.com"
private const val AUD = "test-audience"
private const val HOST = "vesta.example.com"

/** 테스트용 Access: RSA 키를 만들어 JWKS와 서명한 토큰을 낸다 */
private class FakeAccess(val kid: String = "k1") {
    val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    var fetches = 0
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun jwks(): String {
        val pub = keys.public as RSAPublicKey
        fun enc(n: java.math.BigInteger) = b64.encodeToString(n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"keys":[{"kid":"$kid","kty":"RSA","alg":"RS256","use":"sig","n":"${enc(pub.modulus)}","e":"${enc(pub.publicExponent)}"}]}"""
    }

    fun token(
        email: String = "mina@example.com", aud: String = AUD, iss: String = "https://$TEAM", exp: Long = NOW + 600,
        alg: String = "RS256", kid: String = this.kid, signer: KeyPair = keys,
    ): String {
        val header = b64.encodeToString("""{"alg":"$alg","kid":"$kid","typ":"JWT"}""".toByteArray())
        val payload = b64.encodeToString("""{"aud":["$aud"],"email":"$email","exp":$exp,"iat":${NOW - 10},"nbf":${NOW - 10},"iss":"$iss","type":"app"}""".toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(signer.private); update("$header.$payload".toByteArray()); sign() }
        return "$header.$payload.${b64.encodeToString(signature)}"
    }

    fun verifier(clock: Clock = FIXED) = AccessVerifier(TEAM, AUD, fetchKeys = { url ->
        url shouldBe "https://$TEAM/cdn-cgi/access/certs"
        fetches++
        jwks()
    }, clock = clock)

    companion object {
        const val NOW = 1_800_000_000L
        val FIXED: Clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)
    }
}

private class EchoConversation : ConversationService {
    override fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent> = flow {
        emit(AgentEvent.Token("user=${user.userId.value}"))
        emit(AgentEvent.Done(AgentType.CHAT, "m", null))
    }
    override suspend fun resolveApproval(user: UserContext, requestId: String, decision: ApprovalDecision) = true
}

private object GateSessions : SessionRepository {
    override suspend fun create(userId: UserId, channel: ChannelType, title: String?) = error("x")
    override suspend fun find(userId: UserId, id: SessionId): Session? = null
    override suspend fun listRecent(userId: UserId, limit: Int) = emptyList<Session>()
    override suspend fun updateTitle(userId: UserId, id: SessionId, title: String) = Unit
}

private object GateMessages : MessageRepository {
    override suspend fun append(sessionId: SessionId, message: NewMessage): StoredMessage = error("x")
    override suspend fun list(sessionId: SessionId) = emptyList<StoredMessage>()
}

class PublicAccessTest : FunSpec({
    test("Access 토큰: 서명·aud·iss·만료·알고리즘을 확인하고 이메일을 꺼낸다") {
        val access = FakeAccess()
        val verifier = access.verifier()
        verifier.verify(access.token(email = "Mina@Example.com")) shouldBe AccessVerifier.Verified.Ok("mina@example.com")
        verifier.verify(access.token(aud = "other")).shouldBeInstanceOf<AccessVerifier.Verified.Invalid>()
        verifier.verify(access.token(iss = "https://evil.cloudflareaccess.com")).shouldBeInstanceOf<AccessVerifier.Verified.Invalid>()
        verifier.verify(access.token(exp = FakeAccess.NOW - 120)).shouldBeInstanceOf<AccessVerifier.Verified.Invalid>()
        verifier.verify(access.token(alg = "none")).shouldBeInstanceOf<AccessVerifier.Verified.Invalid>()
        // 다른 키로 서명 (같은 kid를 붙여도)
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        verifier.verify(access.token(signer = stranger)).shouldBeInstanceOf<AccessVerifier.Verified.Invalid>()
        // 내용을 바꾸면 서명이 맞지 않는다
        val parts = access.token().split('.')
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
            String(Base64.getUrlDecoder().decode(parts[1])).replace("mina@example.com", "owner@example.com").toByteArray(),
        )
        verifier.verify("${parts[0]}.$forged.${parts[2]}").shouldBeInstanceOf<AccessVerifier.Verified.Invalid>()
        verifier.verify("not-a-token").shouldBeInstanceOf<AccessVerifier.Verified.Invalid>()
    }

    test("모르는 kid가 와도 키를 1분에 한 번까지만 다시 받는다") {
        val access = FakeAccess()
        val verifier = access.verifier()
        verifier.verify(access.token())
        repeat(5) { verifier.verify(access.token(kid = "unknown-$it")) }
        access.fetches shouldBe 1
    }

    fun publicDeps(gatePort: Int, access: FakeAccess, ownerAdmin: Boolean = false): Triple<ServerDeps, UserService, UserId> {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val auth = WebAuthRepository(db.db)
        val repo = ExposedUserRepository(db.db)
        val users = UserService(repo, auth)
        runBlocking {
            auth.setPasswordHash(owner, PasswordHasher.hash("correct horse"))
            users.create("mina", UserRole.GUEST, "mina-pass-1")
            users.setEmail("mina", "mina@example.com")
            users.setEmail("owner", "owner@example.com")
        }
        val deps = ServerDeps(
            EchoConversation(), GateSessions, GateMessages, UserContext(owner, UserRole.OWNER), token = "local-api-key", version = "t",
            auth = WebAuth(auth), users = users,
            publicGate = PublicGate(gatePort, HOST, access.verifier(), ownerAdmin),
            userEmail = { repo.get(it)?.email },
        )
        return Triple(deps, users, owner)
    }

    // 테스트 엔진의 요청은 포트 80으로 들어온다: 터널 입구를 80으로 두면 모든 요청이 바깥 요청이다
    test("터널 입구: Host·Access 토큰이 있어야 하고, 토큰 API(/v1·Bearer)는 받지 않는다") { testApplication {
        val access = FakeAccess()
        val (deps) = publicDeps(80, access)
        application { vestaApi(deps) }
        val jwt = access.token()
        client.get("/api/health") { header("Host", HOST) }.status shouldBe HttpStatusCode.Forbidden
        client.get("/api/health") { header("Host", "127.0.0.1"); header(ACCESS_HEADER, jwt) }.status shouldBe HttpStatusCode.Forbidden
        client.get("/api/health") { header("Host", HOST); header(ACCESS_HEADER, jwt) }.status shouldBe HttpStatusCode.OK
        client.get("/api/health") { header("Host", HOST); header(ACCESS_HEADER, access.token(aud = "x")) }.status shouldBe HttpStatusCode.Forbidden
        client.get("/v1/models") { header("Host", HOST); header(ACCESS_HEADER, jwt); bearerAuth("local-api-key") }.status shouldBe HttpStatusCode.NotFound
        client.get("/api/sessions") { header("Host", HOST); header(ACCESS_HEADER, jwt); bearerAuth("local-api-key") }.status shouldBe HttpStatusCode.Unauthorized
    } }

    test("바깥 로그인: Access 이메일과 계정 이메일이 같아야 하고, 관리 기능은 막힌다") { testApplication {
        val access = FakeAccess()
        val (deps, users) = publicDeps(80, access)
        application { vestaApi(deps) }
        val minaJwt = access.token(email = "mina@example.com")
        val ownerJwt = access.token(email = "owner@example.com")
        // mina의 Access 로그인으로 주인 계정에 들어갈 수 없다. 비밀번호가 맞아도 틀린 것과 같은 답이고, 주인 계정 잠금도 쌓이지 않는다
        val right = client.post("/api/login") { header("Host", HOST); header(ACCESS_HEADER, minaJwt); setBody("""{"password":"correct horse"}""") }
        val wrong = client.post("/api/login") { header("Host", HOST); header(ACCESS_HEADER, minaJwt); setBody("""{"password":"wrong pass"}""") }
        right.status shouldBe HttpStatusCode.Unauthorized
        right.bodyAsText() shouldBe wrong.bodyAsText()
        client.post("/api/login") { header("Host", HOST); header(ACCESS_HEADER, minaJwt); setBody("""{"name":"ghost","password":"x"}""") }.bodyAsText() shouldBe wrong.bodyAsText()
        repeat(6) { client.post("/api/login") { header("Host", HOST); header(ACCESS_HEADER, minaJwt); setBody("""{"name":"owner","password":"x"}""") } }

        val login = client.post("/api/login") { header("Host", HOST); header(ACCESS_HEADER, minaJwt); setBody("""{"name":"mina","password":"mina-pass-1"}""") }
        login.status shouldBe HttpStatusCode.OK
        val setCookie = login.headers[io.ktor.http.HttpHeaders.SetCookie].orEmpty()
        setCookie shouldContain "Secure"
        val cookie = setCookie.substringBefore(';')
        val csrf = dev.vesta.api.ApiJson.decodeFromString(dev.vesta.api.MeDto.serializer(), login.bodyAsText()).csrf
        client.post("/api/chat") { header("Host", HOST); header(ACCESS_HEADER, minaJwt); header("Cookie", cookie); header("X-Vesta-CSRF", csrf); setBody("""{"text":"hi"}""") }
            .bodyAsText() shouldContain "user="
        // 쿠키를 훔쳐도 다른 Access 로그인으로는 못 쓴다
        client.get("/api/sessions") { header("Host", HOST); header(ACCESS_HEADER, ownerJwt); header("Cookie", cookie) }.status shouldBe HttpStatusCode.Forbidden
        // 이메일 연결을 지우면 바로 막힌다
        users.setEmail("mina", null)
        client.get("/api/sessions") { header("Host", HOST); header(ACCESS_HEADER, minaJwt); header("Cookie", cookie) }.status shouldBe HttpStatusCode.Forbidden

        // 주인: 바깥에서 로그인·대화는 되지만 관리 기능은 막힌다
        val ownerLogin = client.post("/api/login") { header("Host", HOST); header(ACCESS_HEADER, ownerJwt); setBody("""{"password":"correct horse"}""") }
        ownerLogin.status shouldBe HttpStatusCode.OK
        val ownerCookie = ownerLogin.headers[io.ktor.http.HttpHeaders.SetCookie].orEmpty().substringBefore(';')
        client.get("/api/sessions") { header("Host", HOST); header(ACCESS_HEADER, ownerJwt); header("Cookie", ownerCookie) }.status shouldBe HttpStatusCode.OK
        val blocked = client.get("/api/users") { header("Host", HOST); header(ACCESS_HEADER, ownerJwt); header("Cookie", ownerCookie) }
        blocked.status shouldBe HttpStatusCode.Forbidden
        blocked.bodyAsText() shouldContain "이 Mac"
    } }

    test("ownerAdmin이면 바깥에서도 주인 관리 기능, 이 Mac 포트는 그대로") { testApplication {
        val access = FakeAccess()
        val (deps) = publicDeps(80, access, ownerAdmin = true)
        application { vestaApi(deps) }
        val ownerJwt = access.token(email = "owner@example.com")
        val cookie = client.post("/api/login") { header("Host", HOST); header(ACCESS_HEADER, ownerJwt); setBody("""{"password":"correct horse"}""") }
            .headers[io.ktor.http.HttpHeaders.SetCookie].orEmpty().substringBefore(';')
        client.get("/api/users") { header("Host", HOST); header(ACCESS_HEADER, ownerJwt); header("Cookie", cookie) }.status shouldBe HttpStatusCode.OK
    } }

    test("이 Mac 포트(터널 입구가 아닌 곳)는 Access 없이 지금처럼") { testApplication {
        val access = FakeAccess()
        val (deps) = publicDeps(8788, access)
        application { vestaApi(deps) }
        client.get("/api/users") { bearerAuth("local-api-key") }.status shouldBe HttpStatusCode.OK
        client.get("/v1/models") { bearerAuth("local-api-key") }.status shouldBe HttpStatusCode.OK
        // 공개 주소 Host로 이 Mac 포트에 와도 받지 않는다 (allowedHosts에 없음)
        client.get("/api/health") { header("Host", HOST) }.status shouldBe HttpStatusCode.Forbidden
    } }
})
