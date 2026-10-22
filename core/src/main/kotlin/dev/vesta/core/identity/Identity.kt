package dev.vesta.core.identity

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import java.time.Instant

/** 사용자 (PROJECT.md 7.12, 11장 User) */
data class User(
    val id: UserId,
    val name: String,
    val role: UserRole,
    val hasPassword: Boolean,
    /** 학습 데이터로 써도 되는지 (L-09). OWNER는 항상 쓴다 */
    val trainingConsent: Boolean,
    val createdAt: Instant,
) {
    val context get() = UserContext(id, role)
}

/** 오늘 쓴 양 (U-05) */
data class DailyUsage(val questions: Int, val completionTokens: Long)

/** 사용자 저장소 (U-01). 이름은 사용자마다 다르다 */
interface UserRepository {
    suspend fun list(): List<User>
    suspend fun find(name: String): User?
    suspend fun get(id: UserId): User?
    suspend fun create(name: String, role: UserRole, passwordHash: String?): User
    suspend fun setRole(id: UserId, role: UserRole): Boolean
    suspend fun setConsent(id: UserId, consent: Boolean): Boolean

    /** 사용자와 그 데이터(대화·컬렉션·미디어 정보·메신저 연결·로그인 세션·피드백)를 지운다. 미디어 파일은 호출한 쪽이 지운다 */
    suspend fun delete(id: UserId): Boolean

    /** [since] 이후 사용자별 질문 수·생성 토큰 */
    suspend fun usageSince(since: Instant): Map<UserId, DailyUsage>
}
