package dev.vesta.core.session

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import java.time.Instant

data class Session(
    val id: SessionId,
    val userId: UserId,
    val title: String?,
    val originChannel: ChannelType,
    val systemPrompt: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StoredMessage(
    val id: Long,
    val sessionId: SessionId,
    val role: ChatRole,
    val content: String,
    val agent: AgentType?,
    val model: String?,
    /** 생성이 중간에 멈췄으면 true (사용자 중단·오류). */
    val interrupted: Boolean,
    val createdAt: Instant,
    /** 붙인 이미지 등 (MediaAsset) */
    val attachments: List<dev.vesta.core.media.MediaAsset> = emptyList(),
)

data class NewMessage(
    val role: ChatRole,
    val content: String,
    val agent: AgentType? = null,
    val model: String? = null,
    val interrupted: Boolean = false,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** 이 메시지에 붙일, 이미 저장한 MediaAsset ID */
    val attachments: List<String> = emptyList(),
)

/** 세션 저장소. 조회는 항상 사용자 범위로 제한한다 (PROJECT.md U-03). */
interface SessionRepository {
    suspend fun create(userId: UserId, channel: ChannelType, title: String?): Session
    suspend fun find(userId: UserId, id: SessionId): Session?
    suspend fun listRecent(userId: UserId, limit: Int): List<Session>
    suspend fun updateTitle(userId: UserId, id: SessionId, title: String)
    /** 세션과 메시지·라우팅 기록을 지운다 (평가용으로 만든 대화 정리, M16). 지웠으면 true */
    suspend fun delete(userId: UserId, id: SessionId): Boolean = false
    /** 이 대화 지침 (C-06, M34 Q-03). null이면 지운다. 본인 대화가 아니면 false */
    suspend fun updateSystemPrompt(userId: UserId, id: SessionId, prompt: String?): Boolean = false
}

/** 이 대화 지침 (C-06, M34 Q-03): 기본 시스템 프롬프트 뒤에 붙는다 (바꾸지 않는다: 날짜·안전 규칙은 그대로) */
object SessionPrompt {
    const val MAX = 4_000

    /** 문제가 있으면 그 이유 */
    fun problem(text: String): String? = when {
        text.length > MAX -> "지침은 ${MAX}자까지입니다"
        dev.vesta.core.text.HiddenChars.has(text) -> "보이지 않는 문자가 들어 있습니다"
        else -> null
    }
}

interface MessageRepository {
    suspend fun append(sessionId: SessionId, message: NewMessage): StoredMessage
    suspend fun list(sessionId: SessionId): List<StoredMessage>
}
