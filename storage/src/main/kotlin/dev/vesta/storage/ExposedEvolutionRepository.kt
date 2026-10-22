package dev.vesta.storage

import dev.vesta.core.evolution.CandidateRecord
import dev.vesta.core.evolution.EvolutionRepository
import dev.vesta.core.evolution.EvolutionRun
import dev.vesta.core.evolution.EvolutionStatus
import dev.vesta.core.evolution.Proposal
import dev.vesta.core.evolution.ProposalKind
import dev.vesta.core.evolution.ProposalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock

private suspend fun <T> Database.tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(this@tx) { block() } }

/** 주간 자가 개선 기록 (PROJECT.md 7.13, M16) */
class ExposedEvolutionRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : EvolutionRepository {
    override suspend fun startRun(): EvolutionRun = db.tx {
        val id = EvolutionRuns.insertAndGetId {
            it[status] = EvolutionStatus.RUNNING.name
            it[startedAt] = clock.instant()
        }.value
        EvolutionRuns.selectAll().where { EvolutionRuns.id eq id }.single().toRun()
    }

    override suspend fun stage(id: Long, stage: String) = db.tx {
        EvolutionRuns.update({ EvolutionRuns.id eq id }) { it[EvolutionRuns.stage] = stage.take(64) }
        Unit
    }

    override suspend fun finishRun(id: Long, status: EvolutionStatus, report: String?, error: String?) = db.tx {
        EvolutionRuns.update({ EvolutionRuns.id eq id }) {
            it[EvolutionRuns.status] = status.name
            it[EvolutionRuns.report] = report
            it[EvolutionRuns.error] = error?.take(4_000)
            it[finishedAt] = clock.instant()
        }
        Unit
    }

    override suspend fun runs(limit: Int): List<EvolutionRun> = db.tx {
        EvolutionRuns.selectAll().orderBy(EvolutionRuns.id, SortOrder.DESC).limit(limit).map { it.toRun() }
    }

    override suspend fun abandonRunning(): Int = db.tx {
        EvolutionRuns.update({ EvolutionRuns.status eq EvolutionStatus.RUNNING.name }) {
            it[status] = EvolutionStatus.FAILED.name
            it[error] = "점검 프로세스가 끝나지 못하고 중단됨"
            it[finishedAt] = clock.instant()
        }
    }

    override suspend fun unnotifiedRuns(): List<EvolutionRun> = db.tx {
        EvolutionRuns.selectAll().where { EvolutionRuns.notifiedAt.isNull() and EvolutionRuns.finishedAt.isNotNull() }
            .orderBy(EvolutionRuns.id).map { it.toRun() }
    }

    override suspend fun markRunNotified(id: Long) = db.tx {
        EvolutionRuns.update({ EvolutionRuns.id eq id }) { it[notifiedAt] = clock.instant() }
        Unit
    }

    override suspend fun addProposal(
        runId: Long?, kind: ProposalKind, title: String, summary: String,
        changes: Map<String, String>, previous: Map<String, String?>, status: ProposalStatus,
    ): Proposal = db.tx {
        val now = clock.instant()
        val id = EvolutionProposals.insertAndGetId {
            it[EvolutionProposals.runId] = runId
            it[EvolutionProposals.kind] = kind.name
            it[EvolutionProposals.title] = title.take(200)
            it[EvolutionProposals.summary] = summary
            it[EvolutionProposals.changes] = encode(changes)
            it[EvolutionProposals.previous] = encode(previous)
            it[EvolutionProposals.status] = status.name
            it[createdAt] = now
            if (status != ProposalStatus.PENDING) it[decidedAt] = now
        }.value
        EvolutionProposals.selectAll().where { EvolutionProposals.id eq id }.single().toProposal()
    }

    override suspend fun proposal(id: Long): Proposal? = db.tx {
        EvolutionProposals.selectAll().where { EvolutionProposals.id eq id }.firstOrNull()?.toProposal()
    }

    override suspend fun proposals(limit: Int): List<Proposal> = db.tx {
        EvolutionProposals.selectAll().orderBy(EvolutionProposals.id, SortOrder.DESC).limit(limit).map { it.toProposal() }
    }

    override suspend fun unnotified(): List<Proposal> = db.tx {
        EvolutionProposals.selectAll().where { EvolutionProposals.notifiedAt.isNull() }.orderBy(EvolutionProposals.id).map { it.toProposal() }
    }

    override suspend fun markNotified(id: Long) = db.tx {
        EvolutionProposals.update({ EvolutionProposals.id eq id }) { it[notifiedAt] = clock.instant() }
        Unit
    }

    override suspend fun transition(id: Long, from: ProposalStatus, to: ProposalStatus): Boolean = db.tx {
        EvolutionProposals.update({ (EvolutionProposals.id eq id) and (EvolutionProposals.status eq from.name) }) {
            it[status] = to.name
            it[decidedAt] = clock.instant()
        } > 0
    }

    override suspend fun candidate(name: String, digest: String): CandidateRecord? = db.tx {
        EvolutionCandidates.selectAll().where { (EvolutionCandidates.name eq name) and (EvolutionCandidates.digest eq digest) }.firstOrNull()?.let {
            CandidateRecord(
                it[EvolutionCandidates.name], it[EvolutionCandidates.digest], it[EvolutionCandidates.result], it[EvolutionCandidates.score],
                it[EvolutionCandidates.note], it[EvolutionCandidates.runId], it[EvolutionCandidates.checkedAt],
            )
        }
    }

    override suspend fun recordCandidate(record: CandidateRecord) = db.tx {
        EvolutionCandidates.upsert {
            it[name] = record.name
            it[digest] = record.digest
            it[result] = record.result
            it[score] = record.score
            it[note] = record.note?.take(4_000)
            it[runId] = record.runId
            it[checkedAt] = record.checkedAt
        }
        Unit
    }

    private fun ResultRow.toRun() = EvolutionRun(
        id = this[EvolutionRuns.id].value,
        status = EvolutionStatus.valueOf(this[EvolutionRuns.status]),
        stage = this[EvolutionRuns.stage],
        report = this[EvolutionRuns.report],
        error = this[EvolutionRuns.error],
        startedAt = this[EvolutionRuns.startedAt],
        finishedAt = this[EvolutionRuns.finishedAt],
    )

    private fun ResultRow.toProposal() = Proposal(
        id = this[EvolutionProposals.id].value,
        runId = this[EvolutionProposals.runId],
        kind = ProposalKind.valueOf(this[EvolutionProposals.kind]),
        title = this[EvolutionProposals.title],
        summary = this[EvolutionProposals.summary],
        changes = decode(this[EvolutionProposals.changes]).mapValues { it.value.orEmpty() },
        previous = decode(this[EvolutionProposals.previous]),
        status = ProposalStatus.valueOf(this[EvolutionProposals.status]),
        createdAt = this[EvolutionProposals.createdAt],
        notifiedAt = this[EvolutionProposals.notifiedAt],
        decidedAt = this[EvolutionProposals.decidedAt],
    )

    private companion object {
        /** 줄마다 `키=값`, 값이 없으면(null) `키`만. 키·값은 설정 키와 모델 이름·숫자라 줄바꿈이 없다 */
        fun encode(map: Map<String, String?>): String =
            map.entries.joinToString("\n") { (k, v) -> if (v == null) k else "$k=${v.replace("\n", " ")}" }

        fun decode(text: String): Map<String, String?> =
            text.lines().filter { it.isNotBlank() }.associate { line ->
                val i = line.indexOf('=')
                if (i < 0) line to null else line.substring(0, i) to line.substring(i + 1)
            }
    }
}
