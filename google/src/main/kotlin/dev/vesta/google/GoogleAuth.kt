package dev.vesta.google

import dev.vesta.core.UserId
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/** 사용자 한 명의 Google 연결 (갱신 토큰은 비밀) */
data class GoogleCredentials(val email: String, val refreshToken: String)

/** 갱신 토큰 저장소 (앱은 키체인 `dev.vesta.google`/`user-<id>`) */
interface GoogleTokenStore {
    fun read(user: UserId): GoogleCredentials?
    fun write(user: UserId, credentials: GoogleCredentials): Boolean
    fun delete(user: UserId)
}

/**
 * 사용자별 Google OAuth (PROJECT.md 7.34 GW-02). 웹 설정에서 [connectUrl]로 Google 로그인에 보내고, 돌아오면 [finish]로 토큰을 받는다.
 * state는 한 번만·10분·사용자에 묶이고, PKCE를 함께 쓴다. 연결을 시작한 브라우저에도 묶는다(쿠키의 nonce, 보안 검토):
 * 남이 자기 state가 든 로그인 링크를 보내 피해자 Google 계정을 자기 Vesta에 붙이지 못하게. 접근 토큰은 메모리에만 두고 만료되면 갱신 토큰으로 다시 받는다
 */
class GoogleAuth(
    private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: () -> String?,
    /** 등록한 돌아오는 주소들 (공개 주소, localhost). 연결을 시작한 화면과 같은 곳으로 돌아와야 쿠키가 따라온다 */
    val redirectUris: List<String>,
    private val store: GoogleTokenStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val user: UserId, val verifier: String, val nonceHash: ByteArray, val redirectUri: String, val expires: Long)

    /** 연결 시작: Google 로그인 [url]과, 브라우저 쿠키로 둘 [nonce]. 돌아오는 곳은 [redirectUri] */
    class Start(val url: String, val nonce: String, val redirectUri: String)
    private class Access(val token: String, val expires: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val access = ConcurrentHashMap<Long, Access>()
    private val known = ConcurrentHashMap<Long, Optional<GoogleCredentials>>()
    private val locks = ConcurrentHashMap<Long, Mutex>()
    @Volatile private var secret: String? = null

    sealed interface Finish {
        data class Ok(val user: UserId, val email: String) : Finish
        data class Failed(val message: String) : Finish
    }

    /** 화면 주소 [origin](`https://host`, `http://localhost:8787`)으로 돌아오는 Google 로그인. 등록한 주소가 아니면 null. 같은 사용자의 이전 요청은 버린다 */
    fun start(user: UserId, origin: String): Start? {
        val redirectUri = redirectUris.firstOrNull { it.startsWith("$origin/") } ?: return null
        val time = now()
        pending.entries.removeIf { it.value.expires < time || it.value.user == user }
        val state = random(32)
        val verifier = random(48)
        val nonce = random(32)
        pending[state] = Pending(user, verifier, sha256(nonce), redirectUri, time + STATE_MILLIS)
        val challenge = b64(sha256(verifier))
        return Start(URLBuilder(AUTH_URL).apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", SCOPES.joinToString(" "))
            // 갱신 토큰을 늘 받도록 (다시 연결해도)
            parameters.append("access_type", "offline")
            parameters.append("prompt", "consent")
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString(), nonce, redirectUri)
    }

    /** Google에서 돌아온 [code]를 토큰으로 바꾸고 저장한다. [nonce]는 연결을 시작한 브라우저의 쿠키 */
    suspend fun finish(state: String, code: String, nonce: String?): Finish {
        val request = pending.remove(state)?.takeIf { it.expires >= now() }
            ?: return Finish.Failed("연결 요청이 없거나 10분이 지났습니다. 설정에서 다시 눌러 주세요")
        if (nonce == null || !MessageDigest.isEqual(sha256(nonce), request.nonceHash))
            return Finish.Failed("연결을 시작한 브라우저가 아닙니다. 연결 버튼을 누른 화면에서 다시 해 주세요")
        val secret = secret() ?: return Finish.Failed("Google 클라이언트 비밀이 없습니다 (Mac에서 vesta google setup)")
        val json = token(
            "grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "client_secret" to secret,
            "redirect_uri" to request.redirectUri, "code_verifier" to request.verifier,
        ) ?: return Finish.Failed("Google이 연결을 받아 주지 않았습니다. 다시 시도해 주세요")
        val accessToken = json.str("access_token") ?: return Finish.Failed("접근 토큰을 받지 못했습니다")
        val refresh = json.str("refresh_token")
            ?: return Finish.Failed("갱신 토큰을 받지 못했습니다. myaccount.google.com/permissions 에서 Vesta를 지우고 다시 연결해 주세요")
        val email = email(accessToken) ?: return Finish.Failed("계정 이메일을 읽지 못했습니다")
        val credentials = GoogleCredentials(email, refresh)
        if (!store.write(request.user, credentials)) return Finish.Failed("토큰을 키체인에 저장하지 못했습니다")
        known[request.user.value] = Optional.of(credentials)
        access[request.user.value] = Access(accessToken, expiry(json))
        return Finish.Ok(request.user, email)
    }

    /** 연결된 계정 이메일. 없으면 null */
    fun email(user: UserId): String? = credentials(user)?.email

    /** 쓸 수 있는 접근 토큰. 연결이 없거나 갱신이 안 되면 null */
    suspend fun accessToken(user: UserId): String? {
        fresh(user)?.let { return it }
        return locks.getOrPut(user.value) { Mutex() }.withLock {
            fresh(user)?.let { return@withLock it }
            val credentials = credentials(user) ?: return@withLock null
            val secret = secret() ?: return@withLock null
            val json = token("grant_type" to "refresh_token", "refresh_token" to credentials.refreshToken, "client_id" to clientId, "client_secret" to secret)
                ?: return@withLock null
            val token = json.str("access_token") ?: return@withLock null
            access[user.value] = Access(token, expiry(json))
            token
        }
    }

    /** Google이 401을 주면 다음에 새로 받게 */
    fun invalidate(user: UserId) {
        access.remove(user.value)
    }

    /** 연결 끊기: Google에서도 토큰을 취소하고 지운다 */
    suspend fun disconnect(user: UserId) {
        credentials(user)?.let { c ->
            runCatching { http.submitForm(REVOKE_URL, Parameters.build { append("token", c.refreshToken) }) }
                .onFailure { log.warn { "Google 토큰 취소 실패 (지우기는 계속): ${it.message}" } }
        }
        store.delete(user)
        known[user.value] = Optional.empty()
        access.remove(user.value)
    }

    private fun fresh(user: UserId) = access[user.value]?.takeIf { it.expires > now() }?.token

    private fun credentials(user: UserId): GoogleCredentials? =
        known.getOrPut(user.value) { Optional.ofNullable(runCatching { store.read(user) }.getOrNull()) }.orElse(null)

    private fun secret(): String? = secret ?: clientSecret()?.also { secret = it }

    private fun expiry(json: JsonObject) = now() + ((json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) - 60) * 1000

    private suspend fun token(vararg fields: Pair<String, String>): JsonObject? {
        val response = runCatching { http.submitForm(TOKEN_URL, Parameters.build { fields.forEach { (k, v) -> append(k, v) } }) }
            .onFailure { log.warn { "Google 토큰 요청 실패: ${it.message}" } }.getOrNull() ?: return null
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // 응답 본문에는 비밀이 없지만, 오류 이름만 남긴다
            log.warn { "Google 토큰 요청 거절 ${response.status.value}: ${runCatching { Json.parseToJsonElement(body).jsonObject.str("error") }.getOrNull()}" }
            return null
        }
        return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private suspend fun email(accessToken: String): String? = runCatching {
        val response = http.get(USERINFO_URL) { bearerAuth(accessToken) }
        if (!response.status.isSuccess()) null else Json.parseToJsonElement(response.bodyAsText()).jsonObject.str("email")
    }.getOrNull()

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
        const val STATE_MILLIS = 10 * 60 * 1000L

        /** 필요한 만큼만 (GW-08): 드라이브는 읽기 + Vesta가 만든 파일만 쓰기 */
        val SCOPES = listOf(
            "openid", "email",
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.file",
            "https://www.googleapis.com/auth/spreadsheets",
            "https://www.googleapis.com/auth/documents",
            "https://www.googleapis.com/auth/calendar.events",
        )

        private val random = SecureRandom()
        private fun random(bytes: Int) = b64(ByteArray(bytes).also(random::nextBytes))
        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
