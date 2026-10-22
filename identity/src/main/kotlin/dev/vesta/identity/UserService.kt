package dev.vesta.identity

import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.identity.DailyUsage
import dev.vesta.core.identity.User
import dev.vesta.core.identity.UserRepository
import dev.vesta.storage.WebAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZoneId

/** 사용자 관리 실패 이유 (화면에 그대로 보여 준다) */
class UserException(message: String) : IllegalArgumentException(message)

/**
 * 사용자 관리 (PROJECT.md U-01·U-02). CLI·웹 UI가 같은 규칙을 쓴다.
 * - OWNER는 한 명(Mac 주인)이고, 지우거나 역할을 바꿀 수 없다. 새 사용자는 MEMBER·GUEST만.
 * - 최대 [maxUsers]명 (7.12: 가족·소규모 팀)
 * - 지우면 그 사용자의 대화·컬렉션·미디어·메신저 연결이 모두 지워진다
 */
class UserService(
    private val users: UserRepository,
    private val auth: WebAuthRepository,
    /** 사용자 미디어 파일 지우기 (`~/.vesta/media/<ID>`) */
    private val deleteMedia: suspend (UserId) -> Unit = {},
    private val maxUsers: Int = 5,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun list(): List<User> = users.list()

    suspend fun find(name: String): User? = users.find(name)

    suspend fun create(name: String, role: UserRole, password: String?): User {
        val clean = name.trim()
        if (!NAME.matches(clean)) throw UserException("이름은 글자·숫자로 시작하는 1~32자(글자·숫자·._-)여야 합니다")
        if (role == UserRole.OWNER) throw UserException("OWNER는 Mac 주인 한 명뿐입니다. MEMBER나 GUEST로 만드세요")
        if (users.find(clean) != null) throw UserException("이미 있는 이름입니다: $clean")
        if (users.list().size >= maxUsers) throw UserException("사용자는 ${maxUsers}명까지입니다")
        password?.let(::checkPassword)
        val hash = password?.let { withContext(Dispatchers.Default) { PasswordHasher.hash(it) } }
        return users.create(clean, role, hash)
    }

    suspend fun setRole(name: String, role: UserRole): User {
        val user = require(name)
        if (user.role == UserRole.OWNER || role == UserRole.OWNER) throw UserException("OWNER 역할은 바꿀 수 없습니다")
        users.setRole(user.id, role)
        return user.copy(role = role)
    }

    /** 비밀번호를 바꾸면 그 사용자의 웹 로그인이 모두 풀린다 */
    suspend fun setPassword(name: String, password: String) {
        val user = require(name)
        checkPassword(password)
        auth.setPasswordHash(user.id, withContext(Dispatchers.Default) { PasswordHasher.hash(password) })
    }

    /** 본인 비밀번호 바꾸기. 지금 비밀번호 확인은 로그인 잠금을 함께 쓰는 쪽(서버 WebAuth.verify)이 먼저 한다 */
    suspend fun setOwnPassword(id: UserId, new: String) {
        checkPassword(new)
        if (users.get(id) == null) throw UserException("없는 사용자입니다")
        auth.setPasswordHash(id, withContext(Dispatchers.Default) { PasswordHasher.hash(new) })
    }

    suspend fun setConsent(name: String, consent: Boolean): User {
        val user = require(name)
        users.setConsent(user.id, consent)
        return user.copy(trainingConsent = consent)
    }

    suspend fun delete(name: String) {
        val user = require(name)
        if (user.role == UserRole.OWNER) throw UserException("OWNER는 지울 수 없습니다")
        users.delete(user.id)
        deleteMedia(user.id)
    }

    /** 오늘(이 Mac 시간대 자정부터) 사용량 */
    suspend fun usageToday(): Map<UserId, DailyUsage> =
        users.usageSince(clock.instant().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant())

    /** 학습에 써도 되는 사용자 (L-09): OWNER와 동의한 사용자 */
    suspend fun trainingUsers(): Set<UserId> = users.list().filter { it.role == UserRole.OWNER || it.trainingConsent }.map { it.id }.toSet()

    private suspend fun require(name: String): User = users.find(name.trim()) ?: throw UserException("없는 사용자입니다: $name")

    private fun checkPassword(password: String) {
        if (password.length < MIN_PASSWORD) throw UserException("비밀번호는 ${MIN_PASSWORD}자 이상이어야 합니다")
    }

    companion object {
        const val MIN_PASSWORD = 8
        /** 글자·숫자로 시작: "."·".."처럼 경로를 벗어나는 이름을 막는다 (MEMBER 폴더 `<memberRoot>/<이름>`) */
        private val NAME = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,31}$")
    }
}
