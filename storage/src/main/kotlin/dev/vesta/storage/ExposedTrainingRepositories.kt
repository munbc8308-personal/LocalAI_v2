package dev.vesta.storage

import dev.vesta.core.AgentType
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.training.Feedback
import dev.vesta.core.training.FeedbackRepository
import dev.vesta.core.training.ModelVersion
import dev.vesta.core.training.ModelVersionRepository
import dev.vesta.core.training.RoutingCorrection
import dev.vesta.core.training.TrainingRepository
import dev.vesta.core.training.TrainingRun
import dev.vesta.core.training.TrainingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock

private suspend fun <T> Database.tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(this@tx) { block() } }

/** 답변 피드백 (C-09). 라우팅 정정은 routing_log.user_override에도 적는다 */
class ExposedFeedbackRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : FeedbackRepository {
    override suspend fun save(userId: UserId, feedback: Feedback): Boolean = db.tx {
        // 자기 세션의 답변(assistant) 메시지인지 확인
        val message = (Messages innerJoin Sessions).selectAll()
            .where { (Messages.id eq feedback.messageId) and (Sessions.userId eq userId.value) }
            .firstOrNull() ?: return@tx false
        if (message[Messages.role] != ChatRole.ASSISTANT.name) return@tx false
        MessageFeedbacks.upsert {
            it[messageId] = feedback.messageId
            it[MessageFeedbacks.userId] = userId.value
            it[rating] = feedback.rating.coerceIn(-1, 1)
            it[correctAgent] = feedback.correctAgent?.name
            it[comment] = feedback.comment?.take(2_000)
            it[updatedAt] = clock.instant()
        }
        feedback.correctAgent?.let { agent ->
            questionBefore(feedback.messageId, message[Messages.sessionId])?.let { question ->
                RoutingLogs.update({ (RoutingLogs.sessionId eq message[Messages.sessionId]) and (RoutingLogs.question eq question) }) {
                    it[userOverride] = agent.name
                }
            }
        }
        true
    }

    override suspend fun find(userId: UserId, messageId: Long): Feedback? = db.tx {
        MessageFeedbacks.selectAll().where { (MessageFeedbacks.messageId eq messageId) and (MessageFeedbacks.userId eq userId.value) }
            .firstOrNull()?.let {
                Feedback(messageId, it[MessageFeedbacks.rating], it[MessageFeedbacks.correctAgent]?.let(AgentType::valueOf), it[MessageFeedbacks.comment])
            }
    }

    override suspend fun routingCorrections(userId: UserId?): List<RoutingCorrection> = db.tx {
        MessageFeedbacks.selectAll()
            .where { if (userId == null) MessageFeedbacks.correctAgent.isNotNull() else (MessageFeedbacks.correctAgent.isNotNull() and (MessageFeedbacks.userId eq userId.value)) }
            .orderBy(MessageFeedbacks.updatedAt, SortOrder.ASC)
            .mapNotNull { row ->
                val messageId = row[MessageFeedbacks.messageId].value
                val session = Messages.selectAll().where { Messages.id eq messageId }.firstOrNull()?.get(Messages.sessionId) ?: return@mapNotNull null
                val question = questionBefore(messageId, session) ?: return@mapNotNull null
                RoutingCorrection(question, AgentType.valueOf(row[MessageFeedbacks.correctAgent]!!), row[MessageFeedbacks.updatedAt])
            }
    }

    /** 이 답변 바로 앞의 사용자 질문 */
    private fun questionBefore(messageId: Long, sessionId: String): String? =
        Messages.selectAll()
            .where { (Messages.sessionId eq sessionId) and (Messages.id less messageId) and (Messages.role eq ChatRole.USER.name) }
            .orderBy(Messages.id, SortOrder.DESC)
            .firstOrNull()?.get(Messages.content)?.takeIf { it.isNotBlank() }
}

class ExposedTrainingRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : TrainingRepository {
    override suspend fun start(kind: String, baseModel: String): TrainingRun = db.tx {
        val id = TrainingRuns.insertAndGetId {
            it[TrainingRuns.kind] = kind
            it[status] = TrainingStatus.RUNNING.name
            it[TrainingRuns.baseModel] = baseModel
            it[startedAt] = clock.instant()
        }.value
        TrainingRuns.selectAll().where { TrainingRuns.id eq id }.single().toRun()
    }

    override suspend fun progress(id: Long, stage: String, step: Int, total: Int, loss: Double?) = db.tx {
        TrainingRuns.update({ TrainingRuns.id eq id }) {
            it[TrainingRuns.stage] = stage
            it[TrainingRuns.step] = step
            it[TrainingRuns.total] = total
            if (loss != null) it[TrainingRuns.loss] = loss
        }
        Unit
    }

    override suspend fun finish(id: Long, status: TrainingStatus, candidate: String?, report: String?, error: String?) = db.tx {
        TrainingRuns.update({ TrainingRuns.id eq id }) {
            it[TrainingRuns.status] = status.name
            it[TrainingRuns.candidate] = candidate
            it[TrainingRuns.report] = report
            it[TrainingRuns.error] = error?.take(4_000)
            it[finishedAt] = clock.instant()
        }
        Unit
    }

    override suspend fun get(id: Long): TrainingRun? = db.tx { TrainingRuns.selectAll().where { TrainingRuns.id eq id }.firstOrNull()?.toRun() }

    override suspend fun list(limit: Int): List<TrainingRun> = db.tx {
        TrainingRuns.selectAll().orderBy(TrainingRuns.id, SortOrder.DESC).limit(limit).map { it.toRun() }
    }

    override suspend fun abandonRunning(): Int = db.tx {
        TrainingRuns.update({ TrainingRuns.status eq TrainingStatus.RUNNING.name }) {
            it[status] = TrainingStatus.FAILED.name
            it[error] = "서버가 다시 시작되어 중단됨"
            it[finishedAt] = clock.instant()
        }
    }

    private fun ResultRow.toRun() = TrainingRun(
        id = this[TrainingRuns.id].value,
        kind = this[TrainingRuns.kind],
        status = TrainingStatus.valueOf(this[TrainingRuns.status]),
        baseModel = this[TrainingRuns.baseModel],
        step = this[TrainingRuns.step],
        total = this[TrainingRuns.total],
        loss = this[TrainingRuns.loss],
        stage = this[TrainingRuns.stage],
        candidate = this[TrainingRuns.candidate],
        report = this[TrainingRuns.report],
        error = this[TrainingRuns.error],
        startedAt = this[TrainingRuns.startedAt],
        finishedAt = this[TrainingRuns.finishedAt],
    )
}

class ExposedModelVersionRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : ModelVersionRepository {
    override suspend fun add(role: String, name: String, baseModel: String, runId: Long?, metrics: String?): ModelVersion = db.tx {
        val id = ModelVersions.insertAndGetId {
            it[ModelVersions.role] = role
            it[ModelVersions.name] = name
            it[ModelVersions.baseModel] = baseModel
            it[ModelVersions.runId] = runId
            it[ModelVersions.metrics] = metrics
            it[createdAt] = clock.instant()
        }.value
        ModelVersions.selectAll().where { ModelVersions.id eq id }.single().toVersion()
    }

    override suspend fun activate(role: String, name: String) = db.tx {
        ModelVersions.update({ (ModelVersions.role eq role) and (ModelVersions.name neq name) }) { it[active] = false }
        ModelVersions.update({ (ModelVersions.role eq role) and (ModelVersions.name eq name) }) { it[active] = true }
        Unit
    }

    override suspend fun active(role: String): ModelVersion? = db.tx {
        ModelVersions.selectAll().where { (ModelVersions.role eq role) and (ModelVersions.active eq true) }.firstOrNull()?.toVersion()
    }

    override suspend fun list(role: String): List<ModelVersion> = db.tx {
        ModelVersions.selectAll().where { ModelVersions.role eq role }.orderBy(ModelVersions.id, SortOrder.DESC).map { it.toVersion() }
    }

    private fun ResultRow.toVersion() = ModelVersion(
        id = this[ModelVersions.id].value,
        role = this[ModelVersions.role],
        name = this[ModelVersions.name],
        baseModel = this[ModelVersions.baseModel],
        runId = this[ModelVersions.runId],
        active = this[ModelVersions.active],
        metrics = this[ModelVersions.metrics],
        createdAt = this[ModelVersions.createdAt],
    )
}
