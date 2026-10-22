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

/** 말투 (U-07) */
enum class Tone { DEFAULT, FORMAL, CASUAL }

/**
 * 사용자별 설정 (PROJECT.md U-07, M20). [nickname]·[tone]은 시스템 프롬프트에, [voiceReply]는 메신저 음성 답장 기본값,
 * [voice]는 읽어 주기 목소리(이 Mac의 `say` 음성 이름)
 */
data class UserSettings(
    val nickname: String? = null,
    val tone: Tone = Tone.DEFAULT,
    val voiceReply: Boolean? = null,
    val voice: String? = null,
) {
    /** 시스템 프롬프트에 붙일 말. 아무것도 정하지 않았으면 null */
    fun persona(): String? = buildList {
        nickname?.let { add("사용자를 '$it'(이)라고 부른다.") }
        when (tone) {
            Tone.FORMAL -> add("항상 정중한 존댓말로 답한다.")
            Tone.CASUAL -> add("친구처럼 편한 반말로 답한다.")
            Tone.DEFAULT -> Unit
        }
    }.takeIf { it.isNotEmpty() }?.joinToString(" ")

    companion object {
        /** 부르는 이름: 글자·숫자·공백 1~20자 (시스템 프롬프트에 들어가므로 지시문을 넣지 못하게 짧고 단순하게) */
        val NICKNAME = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} ]{0,19}$")
    }
}

interface UserSettingsRepository {
    suspend fun get(user: UserId): UserSettings
    suspend fun save(user: UserId, settings: UserSettings)
}
