package dev.vesta.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * 지인 공개 (PROJECT.md 7.20 P-02·P-06, M26). 바깥 입구 포트로 온 요청을 거른다.
 * - Host는 공개 주소 하나만
 * - 토큰 API(`/v1`, CLI Bearer)와 상태 API(`/api/health`)는 바깥에 열지 않는다
 * - IP별 요청 수·로그인 시도 수를 제한한다 (Cloudflare 앞단이 없는 직접 모드에서 특히)
 * - [verifier]가 있으면(cloudflare 모드) Cloudflare Access 토큰(`Cf-Access-Jwt-Assertion`, RS256)을 확인한다: 서명·aud·iss·만료.
 *   Access 설정이 빠졌거나 우회돼도 Vesta가 다시 막는다. 로그인한 사용자의 이메일이 Access 이메일과 같아야 한다 (principal에서)
 * - 직접 모드(공유기 포트포워딩 + Caddy HTTPS)에는 Access가 없어 Vesta 로그인만 남는다: 바깥 로그인은 긴 비밀번호만 받는다
 */
class PublicGate(
    val port: Int,
    val hostname: String,
    private val verifier: AccessVerifier?,
    /** 바깥에서도 Mac 주인의 관리 기능을 쓸지 */
    val ownerAdmin: Boolean = false,
    private val requests: IpLimiter = IpLimiter(limit = 600, window = Duration.ofMinutes(1)),
    private val logins: IpLimiter = IpLimiter(limit = 10, window = Duration.ofMinutes(10)),
) {
    /** 직접 모드 (Cloudflare 없이 공유기 + Caddy) */
    val direct: Boolean get() = verifier == null

    /** 직접 모드: 바깥 로그인 비밀번호 최소 길이 */
    val minPassword: Int? = if (verifier == null) dev.vesta.core.config.PublicAccessConfig.DIRECT_MIN_PASSWORD else null

    /** 거르면 그 이유(HTTP 상태, 글), 통과하면 Access 이메일(직접 모드는 null) */
    sealed interface Result {
        data class Pass(val email: String?) : Result
        data class Reject(val status: Int, val message: String) : Result
    }

    suspend fun check(host: String, path: String, authorization: String?, assertion: String?, ip: String = "?"): Result {
        if (!requests.allow(ip)) return Result.Reject(429, "요청이 너무 많습니다. 잠시 뒤에 다시 시도하세요")
        if (!host.equals(hostname, ignoreCase = true)) return Result.Reject(403, "허용되지 않은 Host")
        // 경로 문자열 비교(/v1)를 비켜 가는 꼴(//·%·./..·끝 /)은 라우팅 전에 거부한다 (보안 검토)
        if (!plainPath(path)) return Result.Reject(400, "잘못된 경로")
        if (path == "/v1" || path.startsWith("/v1/")) return Result.Reject(404, "OpenAI 호환 API는 바깥에 열지 않습니다")
        if (authorization != null) return Result.Reject(401, "토큰 API는 바깥에 열지 않습니다 (웹 로그인을 쓰세요)")
        val email = if (verifier == null) null else {
            val token = assertion ?: return Result.Reject(403, "Cloudflare Access 로그인이 필요합니다")
            when (val verified = verifier.verify(token)) {
                is AccessVerifier.Verified.Ok -> verified.email
                is AccessVerifier.Verified.Invalid -> {
                    log.warn { "Access 토큰 거부: ${verified.reason}" }
                    return Result.Reject(403, "Cloudflare Access 확인 실패")
                }
            }
        }
        return Result.Pass(email)
    }

    /** 로그인 시도 제한 (로그인 라우트 안에서 부른다: 경로 꼴과 상관없이 세도록) */
    fun allowLogin(ip: String): Boolean = logins.allow(ip)

    companion object {
        /** `/`로 시작하고, 빈 칸·`.`·`..` 조각, `%`, 끝 `/`(루트 제외), 제어 문자가 없는 경로 */
        internal fun plainPath(path: String): Boolean {
            if (!path.startsWith("/") || '%' in path || '\\' in path || path.any { it.code < 0x20 || it.code == 0x7f }) return false
            if (path == "/") return true
            if (path.endsWith("/")) return false
            return path.substring(1).split('/').none { it.isEmpty() || it == "." || it == ".." }
        }
    }

}

/** IP별 고정 창 횟수 제한. 기록은 [maxEntries]개까지 (넘으면 지난 창 것부터 비운다) */
class IpLimiter(
    private val limit: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val maxEntries: Int = 10_000,
) {
    private class Slot(var start: Instant, var count: Int)
    private val slots = HashMap<String, Slot>()

    @Synchronized
    fun allow(ip: String): Boolean {
        val now = clock.instant()
        if (slots.size >= maxEntries) {
            slots.values.removeIf { Duration.between(it.start, now) >= window }
            // 모두 지금 창이면 가장 오래된 절반을 버린다 (메모리 고정)
            if (slots.size >= maxEntries) slots.entries.sortedBy { it.value.start }.take(maxEntries / 2).forEach { slots.remove(it.key) }
        }
        val slot = slots.getOrPut(ip) { Slot(now, 0) }
        if (Duration.between(slot.start, now) >= window) { slot.start = now; slot.count = 0 }
        slot.count++
        return slot.count <= limit
    }
}

/**
 * Cloudflare Access JWT 확인. 키는 `https://<팀>.cloudflareaccess.com/cdn-cgi/access/certs`(JWKS)에서 받아 둔다.
 * 키는 6주마다 바뀌므로 모르는 kid가 오면 다시 받는다 (1분에 한 번까지: 엉터리 토큰으로 계속 받게 하지 못하게)
 */
class AccessVerifier(
    teamDomain: String,
    private val audience: String,
    /** JWKS 본문 받기 (테스트에서는 가짜) */
    private val fetchKeys: suspend (url: String) -> String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val team = teamDomain.trim().removePrefix("https://").removeSuffix("/")
    val issuer = "https://$team"
    private val certsUrl = "$issuer/cdn-cgi/access/certs"
    private val mutex = Mutex()
    private var keys: Map<String, PublicKey> = emptyMap()
    private var fetchedAt: Instant = Instant.EPOCH

    sealed interface Verified {
        data class Ok(val email: String) : Verified
        data class Invalid(val reason: String) : Verified
    }

    suspend fun verify(token: String): Verified {
        val parts = token.split('.')
        if (parts.size != 3) return Verified.Invalid("형식")
        val header = runCatching { json(parts[0]) }.getOrNull() ?: return Verified.Invalid("헤더")
        val payload = runCatching { json(parts[1]) }.getOrNull() ?: return Verified.Invalid("내용")
        if (header.string("alg") != "RS256") return Verified.Invalid("알고리즘 ${header.string("alg")}")
        val kid = header.string("kid") ?: return Verified.Invalid("kid 없음")
        val key = key(kid) ?: return Verified.Invalid("모르는 키 $kid")
        val signature = runCatching { B64.decode(parts[2]) }.getOrNull() ?: return Verified.Invalid("서명 형식")
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            runCatching { verify(signature) }.getOrDefault(false)
        }
        if (!ok) return Verified.Invalid("서명")
        if (payload.string("iss") != issuer) return Verified.Invalid("iss ${payload.string("iss")}")
        val aud = when (val value = payload["aud"]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }
        if (audience !in aud) return Verified.Invalid("aud")
        val now = clock.instant().epochSecond
        val exp = payload["exp"]?.jsonPrimitive?.longOrNull ?: return Verified.Invalid("exp 없음")
        if (now > exp + SKEW) return Verified.Invalid("만료")
        payload["nbf"]?.jsonPrimitive?.longOrNull?.let { if (now + SKEW < it) return Verified.Invalid("아직 쓸 수 없음") }
        val email = payload.string("email")?.lowercase()?.takeIf { it.isNotBlank() } ?: return Verified.Invalid("이메일 없음 (서비스 토큰)")
        return Verified.Ok(email)
    }

    private suspend fun key(kid: String): PublicKey? = mutex.withLock {
        val now = clock.instant()
        val stale = Duration.between(fetchedAt, now) > REFRESH
        if (kid !in keys || stale) {
            if (Duration.between(fetchedAt, now) >= MIN_FETCH_INTERVAL) {
                fetchedAt = now
                runCatching { parseKeys(fetchKeys(certsUrl)) }
                    .onSuccess { keys = it }
                    .onFailure { log.warn(it) { "Access 키를 받지 못함: $certsUrl" } }
            }
        }
        keys[kid]
    }

    companion object {
        private val B64: Base64.Decoder = Base64.getUrlDecoder()
        private const val SKEW = 60L
        private val REFRESH: Duration = Duration.ofHours(1)
        private val MIN_FETCH_INTERVAL: Duration = Duration.ofMinutes(1)

        private fun json(part: String): JsonObject = Json.parseToJsonElement(String(B64.decode(part), Charsets.UTF_8)).jsonObject
        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        /** JWKS의 RSA 키 (kid → 공개 키) */
        internal fun parseKeys(body: String): Map<String, PublicKey> {
            val factory = KeyFactory.getInstance("RSA")
            return Json.parseToJsonElement(body).jsonObject["keys"]?.jsonArray.orEmpty().mapNotNull { element ->
                val key = element.jsonObject
                if (key.string("kty") != "RSA") return@mapNotNull null
                val kid = key.string("kid") ?: return@mapNotNull null
                val n = key.string("n") ?: return@mapNotNull null
                val e = key.string("e") ?: return@mapNotNull null
                kid to factory.generatePublic(RSAPublicKeySpec(BigInteger(1, B64.decode(n)), BigInteger(1, B64.decode(e))))
            }.toMap()
        }
    }
}
