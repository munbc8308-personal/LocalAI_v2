package dev.vesta.storage

import dev.vesta.core.ModelRole
import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import dev.vesta.core.studio.AgentRun
import dev.vesta.core.studio.CustomAgentDef
import dev.vesta.core.studio.CustomAgentRepository
import dev.vesta.core.studio.RunEvent
import dev.vesta.core.studio.RunRepository
import dev.vesta.core.studio.RunStatus
import dev.vesta.core.studio.TeamDef
import dev.vesta.core.studio.TeamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock

private suspend fun <T> Database.tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(this@tx) { block() } }

/** 사용자 정의 에이전트 (M22) */
class ExposedCustomAgentRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : CustomAgentRepository {
    override suspend fun list(owner: UserId): List<CustomAgentDef> = db.tx {
        CustomAgents.selectAll().where { CustomAgents.owner eq owner.value }.orderBy(CustomAgents.name).map { it.toDef() }
    }

    override suspend fun find(owner: UserId, name: String): CustomAgentDef? = db.tx {
        CustomAgents.selectAll().where { (CustomAgents.owner eq owner.value) and (CustomAgents.name eq name) }.firstOrNull()?.toDef()
    }

    override suspend fun save(def: CustomAgentDef): CustomAgentDef = db.tx {
        val now = clock.instant()
        val updated = CustomAgents.update({ (CustomAgents.owner eq def.owner.value) and (CustomAgents.name eq def.name) }) {
            it[description] = def.description
            it[instructions] = def.instructions
            it[model] = def.model.name
            it[tools] = def.tools.joinToString("\n")
            it[collections] = def.collections.joinToString("\n")
            it[updatedAt] = now
        }
        if (updated == 0) CustomAgents.insert {
            it[owner] = def.owner.value
            it[name] = def.name
            it[description] = def.description
            it[instructions] = def.instructions
            it[model] = def.model.name
            it[tools] = def.tools.joinToString("\n")
            it[collections] = def.collections.joinToString("\n")
            it[updatedAt] = now
        }
        def.copy(updatedAt = now)
    }

    override suspend fun delete(owner: UserId, name: String): Boolean = db.tx {
        CustomAgents.deleteWhere { (CustomAgents.owner eq owner.value) and (CustomAgents.name eq name) } > 0
    }

    private fun ResultRow.toDef() = CustomAgentDef(
        owner = UserId(this[CustomAgents.owner].value),
        name = this[CustomAgents.name],
        description = this[CustomAgents.description],
        instructions = this[CustomAgents.instructions],
        model = runCatching { ModelRole.valueOf(this[CustomAgents.model]) }.getOrDefault(ModelRole.GENERAL),
        tools = this[CustomAgents.tools].lines().filter { it.isNotBlank() },
        collections = this[CustomAgents.collections].lines().filter { it.isNotBlank() },
        updatedAt = this[CustomAgents.updatedAt],
    )
}

/** 팀 (M22) */
class ExposedTeamRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : TeamRepository {
    override suspend fun list(owner: UserId): List<TeamDef> = db.tx {
        AgentTeams.selectAll().where { AgentTeams.owner eq owner.value }.orderBy(AgentTeams.name).map { it.toDef() }
    }

    override suspend fun find(owner: UserId, name: String): TeamDef? = db.tx {
        AgentTeams.selectAll().where { (AgentTeams.owner eq owner.value) and (AgentTeams.name eq name) }.firstOrNull()?.toDef()
    }

    override suspend fun save(def: TeamDef): TeamDef = db.tx {
        val now = clock.instant()
        val updated = AgentTeams.update({ (AgentTeams.owner eq def.owner.value) and (AgentTeams.name eq def.name) }) {
            it[description] = def.description
            it[leader] = def.leader
            it[members] = def.members.joinToString("\n")
            it[updatedAt] = now
        }
        if (updated == 0) AgentTeams.insert {
            it[owner] = def.owner.value
            it[name] = def.name
            it[description] = def.description
            it[leader] = def.leader
            it[members] = def.members.joinToString("\n")
            it[updatedAt] = now
        }
        def.copy(updatedAt = now)
    }

    override suspend fun delete(owner: UserId, name: String): Boolean = db.tx {
        AgentTeams.deleteWhere { (AgentTeams.owner eq owner.value) and (AgentTeams.name eq name) } > 0
    }

    private fun ResultRow.toDef() = TeamDef(
        owner = UserId(this[AgentTeams.owner].value),
        name = this[AgentTeams.name],
        description = this[AgentTeams.description],
        leader = this[AgentTeams.leader],
        members = this[AgentTeams.members].lines().filter { it.isNotBlank() },
        updatedAt = this[AgentTeams.updatedAt],
    )
}

/** 실행 기록 (M22) */
class ExposedRunRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : RunRepository {
    override suspend fun start(user: UserId, session: SessionId?, agent: String, customAgent: String?, input: String): AgentRun = db.tx {
        val id = AgentRuns.insertAndGetId {
            it[userId] = user.value
            it[sessionId] = session?.value
            it[AgentRuns.agent] = agent
            it[AgentRuns.customAgent] = customAgent
            it[AgentRuns.input] = input.take(4_000)
            it[status] = RunStatus.RUNNING.name
            it[startedAt] = clock.instant()
        }.value
        AgentRuns.selectAll().where { AgentRuns.id eq id }.single().toRun()
    }

    override suspend fun add(run: Long, kind: String, title: String, detail: String?): RunEvent = db.tx {
        val max = RunEvents.seq.max()
        val next = (RunEvents.select(max).where { RunEvents.run eq run }.firstOrNull()?.get(max) ?: 0) + 1
        val now = clock.instant()
        RunEvents.insert {
            it[RunEvents.run] = run
            it[seq] = next
            it[at] = now
            it[RunEvents.kind] = kind
            it[RunEvents.title] = title.take(300)
            it[RunEvents.detail] = detail?.take(8_000)
        }
        RunEvent(run, next, now, kind, title.take(300), detail?.take(8_000))
    }

    override suspend fun setAgent(run: Long, agent: String, customAgent: String?) = db.tx {
        AgentRuns.update({ AgentRuns.id eq run }) { it[AgentRuns.agent] = agent; it[AgentRuns.customAgent] = customAgent }
        Unit
    }

    override suspend fun finish(run: Long, status: RunStatus, answer: String?) = db.tx {
        AgentRuns.update({ AgentRuns.id eq run }) {
            it[AgentRuns.status] = status.name
            it[AgentRuns.answer] = answer?.take(20_000)
            it[finishedAt] = clock.instant()
        }
        Unit
    }

    override suspend fun list(user: UserId?, limit: Int): List<AgentRun> = db.tx {
        AgentRuns.selectAll().apply { if (user != null) where { AgentRuns.userId eq user.value } }
            .orderBy(AgentRuns.id, SortOrder.DESC).limit(limit).map { it.toRun() }
    }

    override suspend fun get(id: Long): AgentRun? = db.tx { AgentRuns.selectAll().where { AgentRuns.id eq id }.firstOrNull()?.toRun() }

    override suspend fun events(run: Long, afterSeq: Int): List<RunEvent> = db.tx {
        RunEvents.selectAll().where { (RunEvents.run eq run) and (RunEvents.seq greater afterSeq) }.orderBy(RunEvents.seq).map {
            RunEvent(run, it[RunEvents.seq], it[RunEvents.at], it[RunEvents.kind], it[RunEvents.title], it[RunEvents.detail])
        }
    }

    override suspend fun abandonRunning(): Int = db.tx {
        AgentRuns.update({ AgentRuns.status eq RunStatus.RUNNING.name }) {
            it[status] = RunStatus.FAILED.name
            it[finishedAt] = clock.instant()
        }
    }

    private fun ResultRow.toRun() = AgentRun(
        id = this[AgentRuns.id].value,
        user = UserId(this[AgentRuns.userId].value),
        session = this[AgentRuns.sessionId]?.let(::SessionId),
        agent = this[AgentRuns.agent],
        customAgent = this[AgentRuns.customAgent],
        input = this[AgentRuns.input],
        status = RunStatus.valueOf(this[AgentRuns.status]),
        answer = this[AgentRuns.answer],
        startedAt = this[AgentRuns.startedAt],
        finishedAt = this[AgentRuns.finishedAt],
    )
}
