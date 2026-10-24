package dev.vesta.storage

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.Session
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.session.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.util.UUID

private suspend fun <T> Database.io(block: () -> T): T = withContext(Dispatchers.IO) { transaction(this@io) { block() } }

class ExposedSessionRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : SessionRepository {

    override suspend fun create(userId: UserId, channel: ChannelType, title: String?): Session = db.io {
        val now = clock.instant()
        val id = UUID.randomUUID().toString()
        Sessions.insert {
            it[Sessions.id] = id
            it[Sessions.userId] = EntityID(userId.value, Users)
            it[Sessions.title] = title
            it[originChannel] = channel.name
            it[createdAt] = now
            it[updatedAt] = now
        }
        Session(SessionId(id), userId, title, channel, null, now, now)
    }

    override suspend fun find(userId: UserId, id: SessionId): Session? = db.io {
        Sessions.selectAll()
            .where { (Sessions.id eq id.value) and (Sessions.userId eq userId.value) }
            .firstOrNull()?.toSession()
    }

    override suspend fun listRecent(userId: UserId, limit: Int): List<Session> = db.io {
        Sessions.selectAll()
            .where { Sessions.userId eq userId.value }
            .orderBy(Sessions.updatedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toSession() }
    }

    override suspend fun updateTitle(userId: UserId, id: SessionId, title: String) {
        db.io {
            Sessions.update({ (Sessions.id eq id.value) and (Sessions.userId eq userId.value) }) {
                it[Sessions.title] = title
            }
        }
    }

    override suspend fun delete(userId: UserId, id: SessionId): Boolean = db.io {
        if (Sessions.selectAll().where { (Sessions.id eq id.value) and (Sessions.userId eq userId.value) }.empty()) return@io false
        // 메시지·피드백은 CASCADE. 라우팅·도구 기록은 세션 ID로만 묶여 있어 따로 지운다
        RoutingLogs.deleteWhere { RoutingLogs.sessionId eq id.value }
        ToolRuns.deleteWhere { ToolRuns.sessionId eq id.value }
        // 이 대화의 실행 기록(에이전트 탭)도. 단계는 CASCADE
        AgentRuns.deleteWhere { (AgentRuns.sessionId eq id.value) and (AgentRuns.userId eq userId.value) }
        ChannelBindings.update({ ChannelBindings.sessionId eq id.value }) { it[sessionId] = null }
        Sessions.deleteWhere { (Sessions.id eq id.value) and (Sessions.userId eq userId.value) } > 0
    }

    private fun ResultRow.toSession() = Session(
        id = SessionId(this[Sessions.id]),
        userId = UserId(this[Sessions.userId].value),
        title = this[Sessions.title],
        originChannel = ChannelType.valueOf(this[Sessions.originChannel]),
        systemPrompt = this[Sessions.systemPrompt],
        createdAt = this[Sessions.createdAt],
        updatedAt = this[Sessions.updatedAt],
    )
}

class ExposedMessageRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : MessageRepository {

    override suspend fun append(sessionId: SessionId, message: NewMessage): StoredMessage = db.io {
        val now = clock.instant()
        val id = Messages.insertAndGetId {
            it[Messages.sessionId] = sessionId.value
            it[role] = message.role.name
            it[content] = message.content
            it[agent] = message.agent?.name
            it[model] = message.model
            it[interrupted] = message.interrupted
            it[promptTokens] = message.promptTokens
            it[completionTokens] = message.completionTokens
            it[createdAt] = now
        }.value
        // 세션 목록이 최근 사용 순으로 정렬되도록 갱신
        Sessions.update({ Sessions.id eq sessionId.value }) { it[updatedAt] = now }
        // 이 세션 주인의 것인 미디어만 붙인다
        val owner = Sessions.selectAll().where { Sessions.id eq sessionId.value }.single()[Sessions.userId].value
        if (message.attachments.isNotEmpty()) {
            MediaAssets.update({ (MediaAssets.id inList message.attachments) and (MediaAssets.userId eq owner) and MediaAssets.messageId.isNull() }) {
                it[messageId] = id
            }
        }
        val attachments = MediaAssets.selectAll().where { MediaAssets.messageId eq id }.map { it.toAsset() }
        StoredMessage(id, sessionId, message.role, message.content, message.agent, message.model, message.interrupted, now, attachments)
    }

    override suspend fun list(sessionId: SessionId): List<StoredMessage> = db.io {
        val attachments = (MediaAssets innerJoin Messages).selectAll()
            .where { Messages.sessionId eq sessionId.value }
            .groupBy({ it[MediaAssets.messageId]!!.value }, { it.toAsset() })
        Messages.selectAll()
            .where { Messages.sessionId eq sessionId.value }
            .orderBy(Messages.id, SortOrder.ASC)
            .map {
                StoredMessage(
                    id = it[Messages.id].value,
                    sessionId = sessionId,
                    role = ChatRole.valueOf(it[Messages.role]),
                    content = it[Messages.content],
                    agent = it[Messages.agent]?.let(AgentType::valueOf),
                    model = it[Messages.model],
                    interrupted = it[Messages.interrupted],
                    createdAt = it[Messages.createdAt],
                    attachments = attachments[it[Messages.id].value].orEmpty(),
                )
            }
    }
}
