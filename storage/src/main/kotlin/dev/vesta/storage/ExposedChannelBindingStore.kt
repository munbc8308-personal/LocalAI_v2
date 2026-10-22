package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.channel.ChannelBinding
import dev.vesta.core.channel.ChannelBindingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Duration

/** 메신저 연결·연결 코드 저장 (M10, PROJECT.md 6.5) */
class ExposedChannelBindingStore(private val db: Database, private val clock: Clock = Clock.systemUTC()) : ChannelBindingStore {
    private suspend fun <T> io(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    override suspend fun find(channel: ChannelType, externalUserId: String): ChannelBinding? = io {
        ChannelBindings.selectAll()
            .where { (ChannelBindings.channel eq channel.name) and (ChannelBindings.externalUserId eq externalUserId) and (ChannelBindings.allowed eq true) }
            .firstOrNull()?.let { toBinding(it) }
    }

    override suspend fun bind(user: UserId, channel: ChannelType, externalUserId: String, externalChatId: String) = io {
        ChannelBindings.deleteWhere { (ChannelBindings.channel eq channel.name) and (ChannelBindings.externalUserId eq externalUserId) }
        ChannelBindings.insert {
            it[userId] = user.value
            it[ChannelBindings.channel] = channel.name
            it[ChannelBindings.externalUserId] = externalUserId
            it[ChannelBindings.externalChatId] = externalChatId
            it[createdAt] = clock.instant()
        }
        Unit
    }

    override suspend fun setSession(channel: ChannelType, externalUserId: String, sessionId: SessionId?) = io {
        ChannelBindings.update({ (ChannelBindings.channel eq channel.name) and (ChannelBindings.externalUserId eq externalUserId) }) {
            it[ChannelBindings.sessionId] = sessionId?.value
        }
        Unit
    }

    override suspend fun list(channel: ChannelType): List<ChannelBinding> = io {
        ChannelBindings.selectAll().where { ChannelBindings.channel eq channel.name }.mapNotNull { toBinding(it) }
    }

    override suspend fun remove(channel: ChannelType, externalUserId: String): Boolean = io {
        ChannelBindings.deleteWhere { (ChannelBindings.channel eq channel.name) and (ChannelBindings.externalUserId eq externalUserId) } > 0
    }

    override suspend fun createPairing(user: UserId, channel: ChannelType, codeHash: String, ttl: Duration) = io {
        // 사용자마다 하나씩: 가족이 동시에 코드를 만들어도 서로의 코드를 지우지 않는다 (M20)
        ChannelPairings.deleteWhere { (ChannelPairings.channel eq channel.name) and (ChannelPairings.userId eq user.value) }
        ChannelPairings.insert {
            it[userId] = user.value
            it[ChannelPairings.channel] = channel.name
            it[ChannelPairings.codeHash] = codeHash
            it[expiresAt] = clock.instant().plus(ttl)
        }
        Unit
    }

    override suspend fun consumePairing(channel: ChannelType, codeHash: String): UserContext? = io {
        val now = clock.instant()
        ChannelPairings.deleteWhere { expiresAt lessEq now }
        val row = ChannelPairings.selectAll()
            .where { (ChannelPairings.channel eq channel.name) and (ChannelPairings.codeHash eq codeHash) and (ChannelPairings.expiresAt greater now) }
            .firstOrNull() ?: return@io null
        ChannelPairings.deleteWhere { ChannelPairings.id eq row[ChannelPairings.id] }
        user(row[ChannelPairings.userId])
    }

    override suspend fun clearPairings(channel: ChannelType) = io {
        ChannelPairings.deleteWhere { ChannelPairings.channel eq channel.name }
        Unit
    }

    private fun toBinding(row: ResultRow): ChannelBinding? {
        val user = user(row[ChannelBindings.userId]) ?: return null
        return ChannelBinding(
            user = user,
            channel = ChannelType.valueOf(row[ChannelBindings.channel]),
            externalUserId = row[ChannelBindings.externalUserId],
            externalChatId = row[ChannelBindings.externalChatId],
            sessionId = row[ChannelBindings.sessionId]?.let(::SessionId),
        )
    }

    private fun user(id: Long): UserContext? =
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.let { UserContext(UserId(it[Users.id].value), it[Users.role]) }
}
