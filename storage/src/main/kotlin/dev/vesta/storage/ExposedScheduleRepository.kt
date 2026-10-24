package dev.vesta.storage

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import dev.vesta.core.schedule.Schedule
import dev.vesta.core.schedule.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Instant

/** 예약 작업 (M29, v26) */
class ExposedScheduleRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : ScheduleRepository {
    private suspend fun <T> io(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    override suspend fun add(user: UserId, task: String, spec: String, channel: ChannelType, nextAt: Instant?): Schedule = io {
        val id = Schedules.insertAndGetId {
            it[userId] = user.value
            it[Schedules.task] = task
            it[Schedules.spec] = spec
            it[Schedules.channel] = channel.name
            it[enabled] = true
            it[Schedules.nextAt] = nextAt
            it[createdAt] = clock.instant()
        }.value
        Schedules.selectAll().where { Schedules.id eq id }.single().toSchedule()
    }

    override suspend fun list(user: UserId): List<Schedule> = io {
        Schedules.selectAll().where { Schedules.userId eq user.value }.orderBy(Schedules.id).map { it.toSchedule() }
    }

    override suspend fun get(id: Long): Schedule? = io { Schedules.selectAll().where { Schedules.id eq id }.firstOrNull()?.toSchedule() }

    // 실행 기록(schedule_run)은 CASCADE로 함께 지워진다. 그 대화는 남는다
    override suspend fun delete(user: UserId, id: Long): Boolean = io {
        Schedules.deleteWhere { (Schedules.id eq id) and (Schedules.userId eq user.value) } > 0
    }

    override suspend fun setEnabled(user: UserId, id: Long, enabled: Boolean, nextAt: Instant?): Boolean = io {
        Schedules.update({ (Schedules.id eq id) and (Schedules.userId eq user.value) }) {
            it[Schedules.enabled] = enabled
            it[Schedules.nextAt] = nextAt
        } > 0
    }

    override suspend fun due(now: Instant): List<Schedule> = io {
        Schedules.selectAll().where { (Schedules.enabled eq true) and (Schedules.nextAt lessEq now) }
            .orderBy(Schedules.nextAt to SortOrder.ASC, Schedules.id to SortOrder.ASC).map { it.toSchedule() }
    }

    override suspend fun advance(id: Long, nextAt: Instant?, lastAt: Instant?, lastStatus: String?) = io {
        Schedules.update({ Schedules.id eq id }) {
            it[Schedules.nextAt] = nextAt
            if (nextAt == null) it[enabled] = false
            if (lastAt != null) it[Schedules.lastAt] = lastAt
            if (lastStatus != null) it[Schedules.lastStatus] = lastStatus.take(300)
        }
        Unit
    }

    override suspend fun addRun(id: Long, session: SessionId, at: Instant) = io {
        ScheduleRuns.insert {
            it[schedule] = id
            it[sessionId] = session.value
            it[ScheduleRuns.at] = at
        }
        Unit
    }

    override suspend fun trimRuns(id: Long, keep: Int): List<SessionId> = io {
        val old = ScheduleRuns.selectAll().where { ScheduleRuns.schedule eq id }
            .orderBy(ScheduleRuns.id to SortOrder.DESC).drop(keep)
        val ids = old.map { it[ScheduleRuns.id].value }
        if (ids.isNotEmpty()) ScheduleRuns.deleteWhere { ScheduleRuns.id inList ids }
        old.map { SessionId(it[ScheduleRuns.sessionId]) }
    }

    private fun ResultRow.toSchedule() = Schedule(
        id = this[Schedules.id].value,
        user = UserId(this[Schedules.userId].value),
        task = this[Schedules.task],
        spec = this[Schedules.spec],
        channel = runCatching { ChannelType.valueOf(this[Schedules.channel]) }.getOrDefault(ChannelType.WEB),
        enabled = this[Schedules.enabled],
        nextAt = this[Schedules.nextAt],
        lastAt = this[Schedules.lastAt],
        lastStatus = this[Schedules.lastStatus],
        createdAt = this[Schedules.createdAt],
    )
}
