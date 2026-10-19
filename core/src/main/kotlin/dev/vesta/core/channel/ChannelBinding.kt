package dev.vesta.core.channel

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import java.time.Duration

/** 메신저 계정 ↔ Vesta 사용자 연결 (PROJECT.md 6.5 허용 목록, U-02). 연결된 계정의 메시지만 처리한다. */
data class ChannelBinding(
    val user: UserContext,
    val channel: ChannelType,
    val externalUserId: String,
    val externalChatId: String,
    /** 이 대화방이 이어 가는 세션. `/new`로 비운다 */
    val sessionId: SessionId?,
)

/**
 * 연결 저장소. 연결은 이 Mac에서 만든 일회용 코드(`vesta telegram pair`)를 봇에게 보내야 생긴다.
 * 코드는 해시로만 저장한다.
 */
interface ChannelBindingStore {
    suspend fun find(channel: ChannelType, externalUserId: String): ChannelBinding?
    suspend fun bind(user: UserId, channel: ChannelType, externalUserId: String, externalChatId: String)
    suspend fun setSession(channel: ChannelType, externalUserId: String, sessionId: SessionId?)
    suspend fun list(channel: ChannelType): List<ChannelBinding>
    suspend fun remove(channel: ChannelType, externalUserId: String): Boolean

    /** 기존 코드는 지우고 새 코드 하나만 남긴다 */
    suspend fun createPairing(user: UserId, channel: ChannelType, codeHash: String, ttl: Duration)
    /** 맞으면 코드를 지우고 사용자를 돌려준다 (한 번만 쓸 수 있음) */
    suspend fun consumePairing(channel: ChannelType, codeHash: String): UserContext?
    suspend fun clearPairings(channel: ChannelType)
}
