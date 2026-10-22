package dev.vesta.server

import dev.vesta.core.UserContext
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
     * 있는 계정은 사용자 ID로 센다: 이름을 비운 로그인과 이름을 적은 로그인(OWNER)이 같은 횟수를 쓰고, 지우지 않는다.
     * 없는 이름은 이름마다 따로 센다(이름 해시). 하나로 묶으면 그 칸이 잠긴 뒤 "잠김 = 없는 이름"으로 계정 유무가 드러난다.
     * 없는 이름 기록만 오래된 것부터 지운다: 지워져도 그 이름은 어차피 로그인할 수 없고, 있는 계정의 횟수에는 영향이 없다.
     */
    private val accounts = HashMap<Long, Attempts>()
    private val unknown = object : LinkedHashMap<String, Attempts>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Attempts>) = size > MAX_UNKNOWN
    }

    suspend fun login(name: String?, password: String, userAgent: String?): LoginResult = mutex.withLock {
        val login = name?.trim()?.takeIf { it.isNotBlank() }
        val credential = repo.credential(login)
        // 비밀번호를 아직 정하지 않았다는 안내는 Mac 주인(이름 비움)에게만: 이름을 적은 경우에 알려 주면 계정 유무가 드러난다
        if (login == null && credential != null && credential.passwordHash == null) return LoginResult.NoPassword
        val tries = if (credential != null) accounts.getOrPut(credential.user.userId.value) { Attempts() }
            else unknown.getOrPut(sha256(login.orEmpty().lowercase())) { Attempts() }
        when (val result = check(tries, credential?.passwordHash, password)) {
            null -> Unit
            else -> return result
        }
        val token = randomToken()
        val session = WebSession(credential!!.user, credential.name, randomToken())
        repo.createSession(credential.user.userId, sha256(token), session.csrf, userAgent, sessionTtl)
        return LoginResult.Ok(token, session)
    }

    /**
     * 로그인한 사용자의 비밀번호 확인 (본인 비밀번호 바꾸기). 로그인과 같은 잠금을 쓴다:
     * 세션 쿠키를 훔쳐도 이 경로로 비밀번호를 무제한 대입할 수 없다. 맞으면 null
     */
    suspend fun verify(user: UserContext, password: String): LoginResult? = mutex.withLock {
        val credential = repo.credentialById(user.userId)
        check(accounts.getOrPut(user.userId.value) { Attempts() }, credential?.passwordHash, password)
    }

    /** 잠김·틀림이면 그 결과, 맞으면 null. 해시가 없어도(없는 계정) 같은 시간이 걸리게 계산한다 */
    private suspend fun check(tries: Attempts, hash: String?, password: String): LoginResult? {
        val now = clock.instant()
        if (now.isBefore(tries.lockedUntil)) return LoginResult.Locked(Duration.between(now, tries.lockedUntil).seconds + 1)
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(password, hash ?: DUMMY_HASH) } && hash != null
        if (ok) {
            tries.failures = 0
            return null
        }
        tries.failures++
        if (tries.failures >= MAX_FAILURES) {
            val minutes = minOf(15L, 1L shl minOf(tries.failures - MAX_FAILURES, 4))
            tries.lockedUntil = clock.instant().plus(Duration.ofMinutes(minutes))
        }
        return LoginResult.Invalid
    }

    suspend fun session(token: String): WebSession? = repo.findSession(sha256(token))

    suspend fun logout(token: String) = repo.deleteSession(sha256(token))

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private companion object {
        const val MAX_FAILURES = 5
        /** 기억해 두는 없는 이름 수 */
        const val MAX_UNKNOWN = 1_024
        val DUMMY_HASH: String = PasswordHasher.hash("vesta-dummy-password")

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
