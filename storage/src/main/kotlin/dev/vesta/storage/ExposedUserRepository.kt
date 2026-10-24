package dev.vesta.storage

import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.identity.DailyUsage
import dev.vesta.core.identity.User
import dev.vesta.core.identity.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant

/** 사용자 저장 (PROJECT.md U-01). 비밀번호 해시 계산은 호출하는 쪽(identity PasswordHasher)이 한다 */
class ExposedUserRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : UserRepository {
    private suspend fun <T> io(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    override suspend fun list(): List<User> = io { Users.selectAll().orderBy(Users.id).map { it.toUser() } }

    override suspend fun find(name: String): User? = io { Users.selectAll().where { Users.name eq name }.firstOrNull()?.toUser() }

    override suspend fun get(id: UserId): User? = io { Users.selectAll().where { Users.id eq id.value }.firstOrNull()?.toUser() }

    override suspend fun create(name: String, role: UserRole, passwordHash: String?): User = io {
        val id = Users.insertAndGetId {
            it[Users.name] = name
            it[Users.role] = role
            it[Users.passwordHash] = passwordHash
            it[createdAt] = clock.instant()
        }.value
        Users.selectAll().where { Users.id eq id }.single().toUser()
    }

    override suspend fun setRole(id: UserId, role: UserRole): Boolean = io {
        Users.update({ Users.id eq id.value }) { it[Users.role] = role } > 0
    }

    override suspend fun setConsent(id: UserId, consent: Boolean): Boolean = io {
        Users.update({ Users.id eq id.value }) { it[trainingConsent] = consent } > 0
    }

    override suspend fun setEmail(id: UserId, email: String?): Boolean = io {
        Users.update({ Users.id eq id.value }) { it[Users.email] = email?.lowercase() } > 0
    }

    override suspend fun findByEmail(email: String): User? = io { Users.selectAll().where { Users.email eq email.lowercase() }.firstOrNull()?.toUser() }

    override suspend fun delete(id: UserId): Boolean = io {
        if (Users.selectAll().where { Users.id eq id.value }.empty()) return@io false
        val sessionIds = Sessions.select(Sessions.id).where { Sessions.userId eq id.value }.map { it[Sessions.id] }
        // 메시지·첨부 정보는 세션을 지우면 함께 지워진다 (CASCADE). 라우팅 기록은 세션 ID로만 묶여 있어 따로 지운다
        if (sessionIds.isNotEmpty()) RoutingLogs.deleteWhere { RoutingLogs.sessionId inList sessionIds }
        MessageFeedbacks.deleteWhere { MessageFeedbacks.userId eq id.value }
        Sessions.deleteWhere { Sessions.userId eq id.value }
        // 컬렉션: 키워드 색인(FTS)을 먼저 지우고, 문서·청크·공유는 CASCADE
        val collectionIds = Collections.select(Collections.id).where { Collections.ownerUserId eq id.value }.map { it[Collections.id].value }
        if (collectionIds.isNotEmpty()) {
            exec(
                "DELETE FROM chunks_fts WHERE rowid IN (SELECT c.id FROM chunks c JOIN documents d ON d.id = c.document_id WHERE d.collection_id IN (${collectionIds.joinToString(",")}))",
            )
            Collections.deleteWhere { Collections.ownerUserId eq id.value }
        }
        MediaAssets.deleteWhere { MediaAssets.userId eq id.value }
        VideoProjects.deleteWhere { VideoProjects.userId eq id.value }
        ToolRuns.deleteWhere { ToolRuns.userId eq id.value }
        WebSessions.deleteWhere { WebSessions.userId eq id.value }
        ChannelBindings.deleteWhere { ChannelBindings.userId eq id.value }
        ChannelPairings.deleteWhere { ChannelPairings.userId eq id.value }
        UserSettingsTable.deleteWhere { UserSettingsTable.userId eq id.value }
        Users.deleteWhere { Users.id eq id.value } > 0
    }

    override suspend fun usageSince(since: Instant): Map<UserId, DailyUsage> = io {
        (Messages innerJoin Sessions).select(Sessions.userId, Messages.role, Messages.completionTokens)
            .where { Messages.createdAt greaterEq since }
            .groupBy({ UserId(it[Sessions.userId].value) }) { it[Messages.role] to (it[Messages.completionTokens] ?: 0) }
            .mapValues { (_, rows) -> DailyUsage(rows.count { it.first == "USER" }, rows.sumOf { it.second.toLong() }) }
    }

    private fun ResultRow.toUser() = User(
        id = UserId(this[Users.id].value),
        name = this[Users.name],
        role = this[Users.role],
        hasPassword = this[Users.passwordHash] != null,
        trainingConsent = this[Users.trainingConsent],
        createdAt = this[Users.createdAt],
        email = this[Users.email],
    )
}

/** 사용자별 설정 (U-07, M20). 없으면 기본값 */
class ExposedUserSettingsRepository(private val db: org.jetbrains.exposed.v1.jdbc.Database) : dev.vesta.core.identity.UserSettingsRepository {
    override suspend fun get(user: dev.vesta.core.UserId): dev.vesta.core.identity.UserSettings =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(db) {
                UserSettingsTable.selectAll().where { UserSettingsTable.userId eq user.value }.firstOrNull()?.let {
                    dev.vesta.core.identity.UserSettings(
                        nickname = it[UserSettingsTable.nickname],
                        tone = runCatching { dev.vesta.core.identity.Tone.valueOf(it[UserSettingsTable.tone]) }.getOrDefault(dev.vesta.core.identity.Tone.DEFAULT),
                        voiceReply = it[UserSettingsTable.voiceReply],
                        voice = it[UserSettingsTable.voice],
                        memoryAuto = it[UserSettingsTable.memoryAuto],
                    )
                } ?: dev.vesta.core.identity.UserSettings()
            }
        }

    override suspend fun save(user: dev.vesta.core.UserId, settings: dev.vesta.core.identity.UserSettings) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(db) {
                UserSettingsTable.upsert {
                    it[userId] = user.value
                    it[nickname] = settings.nickname
                    it[tone] = settings.tone.name
                    it[voiceReply] = settings.voiceReply
                    it[voice] = settings.voice
                    it[memoryAuto] = settings.memoryAuto
                }
            }
            Unit
        }
}
