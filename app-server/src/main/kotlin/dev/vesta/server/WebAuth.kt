package dev.vesta.server

import dev.vesta.identity.PasswordHasher
import dev.vesta.storage.WebAuthRepository
import dev.vesta.storage.WebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** 로그인 결과 */
sealed interface LoginResult {
    data class Ok(val token: String, val session: WebSession) : LoginResult
    data object Invalid : LoginResult
    /** 비밀번호를 아직 정하지 않음 (`vesta passwd`) */
    data object NoPassword : LoginResult
    /** 연속 실패로 잠김 */
    data class Locked(val retryAfterSeconds: Long) : LoginResult
}

/**
 * 웹 UI 로그인 (PROJECT.md 6.3, U-02). 쿠키 값은 무작위 32바이트이고 DB에는 SHA-256만 저장한다.
 * 연속 5번 틀리면 1분부터 두 배씩(최대 15분) 잠근다. 잠금은 로그인 이름별이다: 한 사람이 틀려도 다른 가족은 로그인할 수 있다 (M15).
 * 로그인 확인은 한 번에 하나씩 한다: 동시에 여러 요청을 보내 잠금 검사를 함께 통과하는 우회를 막는다.
 */
class WebAuth(
    private val repo: WebAuthRepository,
    val sessionTtl: Duration = Duration.ofDays(30),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()
    private val mutex = Mutex()
    /** 계정별 연속 실패 수와 잠금 끝 시각 */
    private class Attempts(var failures: Int = 0, var lockedUntil: Instant = Instant.EPOCH)

    /**
     * 키는 사용자 ID: 이름을 비운 로그인과 이름을 적은 로그인(OWNER)이 같은 횟수를 쓴다.
     * 없는 이름은 모두 [UNKNOWN] 하나로 센다 → 기록은 사용자 수 + 1개를 넘지 않아 지울 일이 없다
     * (지우는 방식이면 없는 이름을 많이 보내 실제 계정의 실패 횟수를 초기화할 수 있다).
     */
    private val attempts = HashMap<Long, Attempts>()

    suspend fun login(name: String?, password: String, userAgent: String?): LoginResult = mutex.withLock {
        val now = clock.instant()
        val credential = repo.credential(name?.trim()?.takeIf { it.isNotBlank() })
        val tries = attempts.getOrPut(credential?.user?.userId?.value ?: UNKNOWN) { Attempts() }
        if (now.isBefore(tries.lockedUntil)) return LoginResult.Locked(Duration.between(now, tries.lockedUntil).seconds + 1)
        if (credential != null && credential.passwordHash == null) return LoginResult.NoPassword
        // 사용자가 없어도 같은 시간이 걸리게 해시를 계산한다
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(password, credential?.passwordHash ?: DUMMY_HASH) } && credential != null
        if (!ok) {
            tries.failures++
            if (tries.failures >= MAX_FAILURES) {
                val minutes = minOf(15L, 1L shl minOf(tries.failures - MAX_FAILURES, 4))
                tries.lockedUntil = clock.instant().plus(Duration.ofMinutes(minutes))
            }
            return LoginResult.Invalid
        }
        tries.failures = 0
        val token = randomToken()
        val session = WebSession(credential.user, credential.name, randomToken())
        repo.createSession(credential.user.userId, sha256(token), session.csrf, userAgent, sessionTtl)
        return LoginResult.Ok(token, session)
    }

    suspend fun session(token: String): WebSession? = repo.findSession(sha256(token))

    suspend fun logout(token: String) = repo.deleteSession(sha256(token))

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private companion object {
        const val MAX_FAILURES = 5
        /** 없는 이름의 실패를 모으는 키 (사용자 ID는 1부터) */
        const val UNKNOWN = -1L
        val DUMMY_HASH: String = PasswordHasher.hash("vesta-dummy-password")

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
