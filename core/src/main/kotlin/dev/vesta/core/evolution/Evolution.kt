package dev.vesta.core.evolution

import java.time.Instant

enum class EvolutionStatus { RUNNING, DONE, FAILED }

/** 주간 점검 한 번 (PROJECT.md 7.13 E-01). [report]는 사람이 읽는 요약(마크다운) */
data class EvolutionRun(
    val id: Long,
    val status: EvolutionStatus,
    val stage: String?,
    val report: String?,
    val error: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

/** MODEL: 메인 모델 교체(승인 필요), TUNING: 임계값 조정(평가 통과 시 바로 반영, 되돌리기 가능) */
enum class ProposalKind { MODEL, TUNING }

enum class ProposalStatus { PENDING, APPLIED, REJECTED, ROLLED_BACK, EXPIRED }

/**
 * 설정 변경안 (E-05·E-06). [changes]는 `tuned.properties` 키 → 새 값, [previous]는 바꾸기 전 값(null: 키 없음).
 * 반영은 `tuned.properties`를 고치고 서버를 다시 띄우는 것이다
 */
data class Proposal(
    val id: Long,
    val runId: Long?,
    val kind: ProposalKind,
    val title: String,
    /** 메신저 카드·웹 UI에 보여 줄 근거 (마크다운) */
    val summary: String,
    val changes: Map<String, String>,
    val previous: Map<String, String?>,
    val status: ProposalStatus,
    val createdAt: Instant,
    val notifiedAt: Instant?,
    val decidedAt: Instant?,
)

/** 평가한 후보 모델. 같은 digest는 다시 평가하지 않는다 */
data class CandidateRecord(
    val name: String,
    val digest: String,
    /** PASSED / REJECTED / FAILED */
    val result: String,
    val score: Double?,
    val note: String?,
    val runId: Long?,
    val checkedAt: Instant,
)

interface EvolutionRepository {
    suspend fun startRun(): EvolutionRun
    suspend fun stage(id: Long, stage: String)
    suspend fun finishRun(id: Long, status: EvolutionStatus, report: String?, error: String?)
    suspend fun runs(limit: Int): List<EvolutionRun>
    /** 끝나지 못한 실행(프로세스가 죽음)을 실패로 정리한다 */
    suspend fun abandonRunning(): Int
    /** 끝났지만 아직 메신저로 알리지 않은 점검 */
    suspend fun unnotifiedRuns(): List<EvolutionRun>
    suspend fun markRunNotified(id: Long)

    suspend fun addProposal(runId: Long?, kind: ProposalKind, title: String, summary: String, changes: Map<String, String>, previous: Map<String, String?>, status: ProposalStatus): Proposal
    suspend fun proposal(id: Long): Proposal?
    suspend fun proposals(limit: Int): List<Proposal>
    /** 아직 메신저로 알리지 않은 제안 */
    suspend fun unnotified(): List<Proposal>
    suspend fun markNotified(id: Long)
    /** [from] 상태일 때만 [to]로 바꾼다 (두 번 누르기·동시 처리 막기). 바꿨으면 true */
    suspend fun transition(id: Long, from: ProposalStatus, to: ProposalStatus): Boolean

    suspend fun candidate(name: String, digest: String): CandidateRecord?
    suspend fun recordCandidate(record: CandidateRecord)
}
