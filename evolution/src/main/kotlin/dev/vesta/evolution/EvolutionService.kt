package dev.vesta.evolution

import dev.vesta.core.config.TunedSettings
import dev.vesta.core.evolution.EvolutionRepository
import dev.vesta.core.evolution.Proposal
import dev.vesta.core.evolution.ProposalKind
import dev.vesta.core.evolution.ProposalStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/** 제안 처리 결과. [ok]가 false면 [message]가 이유 */
data class ProposalOutcome(val ok: Boolean, val message: String, val proposal: Proposal? = null)

/**
 * 설정 변경안 만들기·승인·거절·되돌리기 (PROJECT.md 7.13 E-05~E-07). CLI·서버(메신저 버튼·웹 UI)가 같은 규칙을 쓴다.
 * 반영은 [tunedFile](`~/.vesta/tuned.properties`)을 고치는 것까지: 서버는 파일이 바뀐 것을 보고, 하던 요청이 없을 때 다시 시작한다.
 * - 메인 모델 교체(MODEL)는 승인해야 반영된다. 기한([ttl])이 지나면 만료
 * - 임계값 조정(TUNING)은 평가를 통과하면 바로 반영하고 알림만 보낸다 (되돌리기 가능)
 * - 제안 뒤 그 설정이 다른 이유로 바뀌었으면 승인하지 않는다 (되돌릴 값이 틀려지므로)
 */
class EvolutionService(
    private val repo: EvolutionRepository,
    private val tunedFile: Path,
    private val ttl: Duration = Duration.ofDays(7),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    fun current(): Map<String, String> = TunedSettings.read(tunedFile)

    /** 승인을 기다리는 제안을 만든다. 같은 종류의 이전 제안은 만료시킨다 (새 점검 결과가 더 정확하다) */
    suspend fun propose(runId: Long?, kind: ProposalKind, title: String, summary: String, changes: Map<String, String>): Proposal = mutex.withLock {
        repo.proposals(50).filter { it.kind == kind && it.status == ProposalStatus.PENDING }
            .forEach { repo.transition(it.id, ProposalStatus.PENDING, ProposalStatus.EXPIRED) }
        val now = current()
        repo.addProposal(runId, kind, title, summary, changes, changes.keys.associateWith { now[it] }, ProposalStatus.PENDING)
    }

    /** 바로 반영하는 변경 (TUNING) */
    suspend fun applyNow(runId: Long?, kind: ProposalKind, title: String, summary: String, changes: Map<String, String>): Proposal = mutex.withLock {
        val now = current()
        val previous = changes.keys.associateWith { now[it] }
        TunedSettings.update(tunedFile, changes)
        log.info { "설정 반영: $title $changes" }
        repo.addProposal(runId, kind, title, summary, changes, previous, ProposalStatus.APPLIED)
    }

    suspend fun approve(id: Long): ProposalOutcome = mutex.withLock {
        val proposal = repo.proposal(id) ?: return ProposalOutcome(false, "없는 제안입니다: #$id")
        if (proposal.status != ProposalStatus.PENDING) return ProposalOutcome(false, "이미 처리한 제안입니다 (${label(proposal.status)})", proposal)
        if (proposal.createdAt.plus(ttl).isBefore(clock.instant())) {
            repo.transition(id, ProposalStatus.PENDING, ProposalStatus.EXPIRED)
            return ProposalOutcome(false, "기한(${ttl.toDays()}일)이 지나 만료된 제안입니다. 다음 점검 결과를 기다리세요", proposal)
        }
        val now = current()
        if (proposal.previous.any { (key, value) -> now[key] != value }) {
            repo.transition(id, ProposalStatus.PENDING, ProposalStatus.EXPIRED)
            return ProposalOutcome(false, "제안 뒤에 같은 설정이 바뀌어 만료시켰습니다", proposal)
        }
        if (!repo.transition(id, ProposalStatus.PENDING, ProposalStatus.APPLIED)) return ProposalOutcome(false, "이미 처리한 제안입니다", proposal)
        TunedSettings.update(tunedFile, proposal.changes)
        log.info { "제안 #$id 승인·반영: ${proposal.changes}" }
        ProposalOutcome(true, "반영했습니다. 하던 답변이 끝나면 서버를 다시 시작합니다", repo.proposal(id))
    }

    suspend fun reject(id: Long): ProposalOutcome = mutex.withLock {
        val proposal = repo.proposal(id) ?: return ProposalOutcome(false, "없는 제안입니다: #$id")
        if (!repo.transition(id, ProposalStatus.PENDING, ProposalStatus.REJECTED)) return ProposalOutcome(false, "이미 처리한 제안입니다 (${label(proposal.status)})", proposal)
        log.info { "제안 #$id 거절" }
        ProposalOutcome(true, "거절했습니다. 지금 설정을 그대로 씁니다", repo.proposal(id))
    }

    /** 반영한 변경을 되돌린다. 그 뒤에 같은 설정을 또 바꿨으면 그것부터 되돌려야 한다 */
    suspend fun rollback(id: Long): ProposalOutcome = mutex.withLock {
        val proposal = repo.proposal(id) ?: return ProposalOutcome(false, "없는 제안입니다: #$id")
        if (proposal.status != ProposalStatus.APPLIED) return ProposalOutcome(false, "반영한 제안만 되돌릴 수 있습니다 (지금: ${label(proposal.status)})", proposal)
        val now = current()
        if (proposal.changes.any { (key, value) -> now[key] != value }) {
            return ProposalOutcome(false, "그 뒤에 같은 설정이 또 바뀌었습니다. 최근 변경부터 되돌리세요", proposal)
        }
        if (!repo.transition(id, ProposalStatus.APPLIED, ProposalStatus.ROLLED_BACK)) return ProposalOutcome(false, "이미 처리한 제안입니다", proposal)
        TunedSettings.update(tunedFile, proposal.previous)
        log.info { "제안 #$id 되돌림: ${proposal.previous}" }
        ProposalOutcome(true, "되돌렸습니다. 하던 답변이 끝나면 서버를 다시 시작합니다", repo.proposal(id))
    }

    /** 가장 최근에 반영한 변경 */
    suspend fun lastApplied(): Proposal? = repo.proposals(50).firstOrNull { it.status == ProposalStatus.APPLIED }

    suspend fun expireOld(): Int = mutex.withLock {
        repo.proposals(100).filter { it.status == ProposalStatus.PENDING && it.createdAt.plus(ttl).isBefore(clock.instant()) }
            .count { repo.transition(it.id, ProposalStatus.PENDING, ProposalStatus.EXPIRED) }
    }

    companion object {
        fun label(status: ProposalStatus) = when (status) {
            ProposalStatus.PENDING -> "승인 대기"
            ProposalStatus.APPLIED -> "반영함"
            ProposalStatus.REJECTED -> "거절함"
            ProposalStatus.ROLLED_BACK -> "되돌림"
            ProposalStatus.EXPIRED -> "만료"
        }

        /** 메신저·CLI에 보여 줄 글 */
        fun describe(p: Proposal): String = buildString {
            append("#${p.id} ${p.title} (${label(p.status)})\n")
            p.changes.forEach { (key, value) -> append("· $key: ${p.previous[key] ?: "(config.yaml 값)"} → $value\n") }
            append("\n").append(p.summary.trim())
        }
    }
}
