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
     * 실패 기록은 고정 크기 표 하나에 둔다. 칸은 이름(소문자, 이름을 비우면 Mac 주인 이름)의 HMAC으로 정하고, 지우지 않는다.
     * - 있는 계정과 없는 이름이 똑같이 동작한다: 잠김 응답이나 기록이 밀려나는지로 계정 유무를 알 수 없다
     * - 지우지 않으므로 다른 이름을 많이 보내 실제 계정의 실패 횟수를 초기화할 수 없다. 메모리는 [SLOTS]칸으로 고정
     * - 이름을 비운 로그인과 이름을 적은 로그인(Mac 주인)이 같은 칸을 쓴다
     * HMAC 키는 서버를 띄울 때마다 새로 만든다 (어느 이름이 같은 칸인지 밖에서 알 수 없게)
     */
    private val slots = Array(SLOTS) { Attempts() }
    private val slotKey = ByteArray(32).also(random::nextBytes)

    private fun slot(name: String): Attempts {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(slotKey, "HmacSHA256")) }
        val digest = mac.doFinal(name.lowercase().toByteArray())
        return slots[java.nio.ByteBuffer.wrap(digest).int.mod(SLOTS)]
    }

    suspend fun login(name: String?, password: String, userAgent: String?): LoginResult = mutex.withLock {
        val login = name?.trim()?.takeIf { it.isNotBlank() }
        val credential = repo.credential(login)
        // 비밀번호를 아직 정하지 않았다는 안내는 Mac 주인(이름 비움)에게만: 이름을 적은 경우에 알려 주면 계정 유무가 드러난다
        if (login == null && credential != null && credential.passwordHash == null) return LoginResult.NoPassword
        val tries = slot(credential?.name ?: login.orEmpty())
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
        check(slot(credential?.name ?: "#${user.userId.value}"), credential?.passwordHash, password)
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
        /** 실패 기록 칸 수 (사용자는 최대 5명이라 서로 겹칠 일이 거의 없다) */
        const val SLOTS = 4_096
        val DUMMY_HASH: String = PasswordHasher.hash("vesta-dummy-password")

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
