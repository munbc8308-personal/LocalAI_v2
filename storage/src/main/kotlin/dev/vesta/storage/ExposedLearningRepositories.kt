package dev.vesta.storage

import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.training.CuratorSource
import dev.vesta.core.training.CuratorTurn
import dev.vesta.core.training.SampleKind
import dev.vesta.core.training.SampleSignal
import dev.vesta.core.training.SampleStatus
import dev.vesta.core.training.TrainingSample
import dev.vesta.core.training.TrainingSampleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock

private suspend fun <T> Database.tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(this@tx) { block() } }

/** 학습 재료 (M25 L-25·L-26) */
class ExposedTrainingSampleRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : TrainingSampleRepository {
    override suspend fun add(sample: TrainingSample): TrainingSample? = db.tx {
        val key = dedupe(sample)
        val now = clock.instant()
        val existing = TrainingSamples.selectAll().where { TrainingSamples.dedupe eq key }.firstOrNull()
        if (existing != null) {
            // 👍·👎는 바꿀 수 있다: 같은 답의 피드백 재료를 새 평가로 고친다 (취소하면 REJECTED)
            if (sample.signal != SampleSignal.FEEDBACK) return@tx null
            TrainingSamples.update({ TrainingSamples.id eq existing[TrainingSamples.id] }) {
                it[label] = sample.label.take(80)
                it[confidence] = sample.confidence
                it[status] = sample.status.name
                it[reviewedAt] = now
            }
            return@tx sample.copy(id = existing[TrainingSamples.id].value, createdAt = existing[TrainingSamples.createdAt])
        }
        val id = TrainingSamples.insertAndGetId {
            it[userId] = sample.userId.value
            it[kind] = sample.kind.name
            it[signal] = sample.signal.name
            it[messageId] = sample.messageId
            it[question] = sample.question
            it[answer] = sample.answer
            it[label] = sample.label.take(80)
            it[confidence] = sample.confidence
            it[status] = sample.status.name
            it[note] = sample.note
            it[dedupe] = key
            it[createdAt] = now
        }.value
        sample.copy(id = id, createdAt = now)
    }

    override suspend fun list(status: SampleStatus?, limit: Int): List<TrainingSample> = db.tx {
        TrainingSamples.selectAll().apply { if (status != null) where { TrainingSamples.status eq status.name } }
            .orderBy(TrainingSamples.id, SortOrder.DESC).limit(limit).map { it.toSample() }
    }

    override suspend fun review(id: Long, status: SampleStatus, label: String?): Boolean = db.tx {
        TrainingSamples.update({ TrainingSamples.id eq id }) {
            it[TrainingSamples.status] = status.name
            label?.let { l -> it[TrainingSamples.label] = l.take(80) }
            it[reviewedAt] = clock.instant()
        } > 0
    }

    override suspend fun counts(): Map<String, Int> = db.tx {
        TrainingSamples.select(TrainingSamples.kind, TrainingSamples.status).map { "${it[TrainingSamples.kind]}:${it[TrainingSamples.status]}" }
            .groupingBy { it }.eachCount()
    }

    override suspend fun accepted(kind: SampleKind, users: Set<UserId>): List<TrainingSample> = db.tx {
        if (users.isEmpty()) return@tx emptyList()
        TrainingSamples.selectAll().where {
            (TrainingSamples.kind eq kind.name) and (TrainingSamples.status eq SampleStatus.ACCEPTED.name) and (TrainingSamples.userId inList users.map { it.value })
        }.orderBy(TrainingSamples.id).map { it.toSample() }
    }

    override suspend fun messageIds(kind: SampleKind): Set<Long> = db.tx {
        TrainingSamples.select(TrainingSamples.messageId).where { TrainingSamples.kind eq kind.name }.mapNotNull { it[TrainingSamples.messageId] }.toSet()
    }

    override suspend fun cursor(): Long = db.tx { CuratorState.selectAll().where { CuratorState.key eq CURSOR }.firstOrNull()?.get(CuratorState.value) ?: 0L }

    override suspend fun setCursor(messageId: Long) {
        db.tx { CuratorState.upsert { it[key] = CURSOR; it[value] = messageId } }
    }

    private fun dedupe(sample: TrainingSample): String {
        // 피드백은 답 하나에 하나 (바꾸면 고친다)
        if (sample.signal == SampleSignal.FEEDBACK) return "${sample.kind}:FEEDBACK:${sample.messageId}"
        val text = sample.question.lowercase().filter { it.isLetterOrDigit() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return "${sample.kind}:${sample.signal.takeIf { sample.kind != SampleKind.ROUTING } ?: "-"}:${sample.messageId ?: "-"}:${sample.label}:$digest".take(200)
    }

    private fun ResultRow.toSample() = TrainingSample(
        id = this[TrainingSamples.id].value,
        userId = UserId(this[TrainingSamples.userId].value),
        kind = SampleKind.valueOf(this[TrainingSamples.kind]),
        signal = SampleSignal.valueOf(this[TrainingSamples.signal]),
        messageId = this[TrainingSamples.messageId],
        question = this[TrainingSamples.question],
        answer = this[TrainingSamples.answer],
        label = this[TrainingSamples.label],
        confidence = this[TrainingSamples.confidence],
        status = SampleStatus.valueOf(this[TrainingSamples.status]),
        note = this[TrainingSamples.note],
        createdAt = this[TrainingSamples.createdAt],
        reviewedAt = this[TrainingSamples.reviewedAt],
    )

    private companion object {
        const val CURSOR = "message_cursor"
    }
}

/** 큐레이터가 볼 대화 턴: 메시지·라우팅 기록·피드백·도구 실행 기록을 묶는다 (M25 L-25) */
class ExposedCuratorSource(private val db: Database) : CuratorSource {
    /** 답 메시지와 그 앞 질문 (피드백 재료용). 답이 아니면 null */
    suspend fun turnOfAnswer(answerId: Long): CuratorTurn? = db.tx {
        val answer = (Messages innerJoin Sessions).selectAll().where { Messages.id eq answerId }.firstOrNull() ?: return@tx null
        if (answer[Messages.role] != ChatRole.ASSISTANT.name) return@tx null
        val session = answer[Messages.sessionId]
        val question = Messages.selectAll().where { (Messages.sessionId eq session) and (Messages.role eq ChatRole.USER.name) and (Messages.id less answerId) }
            .orderBy(Messages.id, SortOrder.DESC).firstOrNull() ?: return@tx null
        CuratorTurn(UserId(answer[Sessions.userId].value), session, question[Messages.id].value, question[Messages.content], question[Messages.createdAt],
            answerId, answer[Messages.content], answer[Messages.agent])
    }

    override suspend fun turns(afterMessageId: Long, users: Set<UserId>, limit: Int): List<CuratorTurn> = db.tx {
        if (users.isEmpty()) return@tx emptyList()
        // 새 질문이 있는 세션 (오래된 것부터)
        val sessions = (Messages innerJoin Sessions).select(Messages.sessionId, Sessions.userId, Messages.id)
            .where { (Messages.id greater afterMessageId) and (Messages.role eq ChatRole.USER.name) and (Sessions.userId inList users.map { it.value }) }
            .orderBy(Messages.id).limit(limit)
            .map { it[Messages.sessionId] to UserId(it[Sessions.userId].value) }.distinctBy { it.first }
        val turns = mutableListOf<CuratorTurn>()
        for ((session, user) in sessions) {
            val messages = Messages.selectAll().where { Messages.sessionId eq session }.orderBy(Messages.id).toList()
            val routes = RoutingLogs.selectAll().where { RoutingLogs.sessionId eq session }.orderBy(RoutingLogs.createdAt).toList()
            val denied = ToolRuns.selectAll().where { (ToolRuns.sessionId eq session) and (ToolRuns.approved eq false) }.toList()
            val questions = messages.withIndex().filter { it.value[Messages.role] == ChatRole.USER.name }
            // 새 질문 바로 앞의 턴부터 (신호를 찾으려면 앞 턴이 필요하다)
            val first = (questions.indexOfFirst { it.value[Messages.id].value > afterMessageId } - 1).coerceAtLeast(0)
            questions.drop(first).forEachIndexed { k, (index, q) ->
                val at = q[Messages.createdAt]
                val nextAt = questions.getOrNull(first + k + 1)?.value?.get(Messages.createdAt)
                val answer = messages.drop(index + 1).takeWhile { it[Messages.role] != ChatRole.USER.name }.firstOrNull { it[Messages.role] == ChatRole.ASSISTANT.name }
                val route = routes.firstOrNull { !it[RoutingLogs.createdAt].isBefore(at.minusSeconds(1)) && (nextAt == null || it[RoutingLogs.createdAt].isBefore(nextAt)) }
                val feedback = answer?.let { a -> MessageFeedbacks.selectAll().where { MessageFeedbacks.messageId eq a[Messages.id] }.firstOrNull() }
                turns += CuratorTurn(
                    userId = user,
                    sessionId = session,
                    questionId = q[Messages.id].value,
                    question = q[Messages.content],
                    questionAt = at,
                    answerId = answer?.get(Messages.id)?.value,
                    answer = answer?.get(Messages.content),
                    answerAgent = answer?.get(Messages.agent),
                    routedStage = route?.get(RoutingLogs.stage),
                    rating = feedback?.get(MessageFeedbacks.rating),
                    correctAgent = feedback?.get(MessageFeedbacks.correctAgent),
                    deniedTools = denied.filter { !it[ToolRuns.createdAt].isBefore(at) && (nextAt == null || it[ToolRuns.createdAt].isBefore(nextAt)) }.map { it[ToolRuns.tool] },
                )
            }
        }
        turns
    }
}
