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
}

interface MessageRepository {
    suspend fun append(sessionId: SessionId, message: NewMessage): StoredMessage
    suspend fun list(sessionId: SessionId): List<StoredMessage>
}
