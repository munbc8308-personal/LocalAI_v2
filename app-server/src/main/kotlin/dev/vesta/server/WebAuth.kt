package dev.vesta.server

import dev.vesta.app.PasswordHasher
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
 * 연속 5번 틀리면 1분부터 두 배씩(최대 15분) 잠근다. 사용자가 한 명이라 잠금은 서버 전체 기준이다.
 * 로그인 확인은 한 번에 하나씩 한다: 동시에 여러 요청을 보내 잠금 검사를 함께 통과하는 우회를 막는다.
 */
class WebAuth(
    private val repo: WebAuthRepository,
    val sessionTtl: Duration = Duration.ofDays(30),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()
    private val mutex = Mutex()
    private var failures = 0
    private var lockedUntil: Instant = Instant.EPOCH

    suspend fun login(name: String?, password: String, userAgent: String?): LoginResult = mutex.withLock {
        val now = clock.instant()
        if (now.isBefore(lockedUntil)) return LoginResult.Locked(Duration.between(now, lockedUntil).seconds + 1)
        val credential = repo.credential(name?.takeIf { it.isNotBlank() })
        if (credential != null && credential.passwordHash == null) return LoginResult.NoPassword
        // 사용자가 없어도 같은 시간이 걸리게 해시를 계산한다
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(password, credential?.passwordHash ?: DUMMY_HASH) } && credential != null
        if (!ok) {
            failures++
            if (failures >= MAX_FAILURES) {
                val minutes = minOf(15L, 1L shl minOf(failures - MAX_FAILURES, 4))
                lockedUntil = clock.instant().plus(Duration.ofMinutes(minutes))
            }
            return LoginResult.Invalid
        }
        failures = 0
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
        val DUMMY_HASH: String = PasswordHasher.hash("vesta-dummy-password")

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
