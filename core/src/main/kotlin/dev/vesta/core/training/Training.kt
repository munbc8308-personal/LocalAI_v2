package dev.vesta.core.training

import dev.vesta.core.AgentType
import dev.vesta.core.UserId
import java.time.Instant

/** 답변 피드백 (PROJECT.md C-09). [correctAgent]는 "이건 ○○ 담당이었어야 해" 라우팅 정정 */
data class Feedback(
    val messageId: Long,
    /** 1 좋음, -1 나쁨, 0 평가 없음(정정만) */
    val rating: Int,
    val correctAgent: AgentType? = null,
    val comment: String? = null,
)

/** 학습 후보가 되는 라우팅 정정 (L-01): 사용자가 고른 담당자와 그때의 질문 */
data class RoutingCorrection(val question: String, val agent: AgentType, val createdAt: Instant)

interface FeedbackRepository {
    /** 자기 세션의 답변 메시지에만 남길 수 있다. 아니면 false */
    suspend fun save(userId: UserId, feedback: Feedback): Boolean
    suspend fun find(userId: UserId, messageId: Long): Feedback?
    /** [userId]가 null이면 모든 사용자 (단일 사용자 동안 OWNER, 멀티 유저에서는 동의한 사용자만 넘긴다, L-09) */
    suspend fun routingCorrections(userId: UserId?): List<RoutingCorrection>
}

enum class TrainingStatus { RUNNING, PASSED, REJECTED, FAILED, CANCELLED }

/** 학습 실행 기록 (PROJECT.md 11장 TrainingRun, L-05·L-06) */
data class TrainingRun(
    val id: Long,
    /** decision (결정 모델) */
    val kind: String,
    val status: TrainingStatus,
    val baseModel: String,
    val step: Int,
    val total: Int,
    val loss: Double?,
    val stage: String?,
    val candidate: String?,
    /** 평가 결과 (JSON) */
    val report: String?,
    val error: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

interface TrainingRepository {
    suspend fun start(kind: String, baseModel: String): TrainingRun
    suspend fun progress(id: Long, stage: String, step: Int, total: Int, loss: Double?)
    suspend fun finish(id: Long, status: TrainingStatus, candidate: String?, report: String?, error: String?)
    suspend fun get(id: Long): TrainingRun?
    suspend fun list(limit: Int): List<TrainingRun>
    /** 서버가 꺼지면서 끝나지 못한 실행을 실패로 정리한다 */
    suspend fun abandonRunning(): Int
}

/** 학습으로 만든 모델 버전 (PROJECT.md 11장 ModelVersion, L-07). 역할마다 하나만 active */
data class ModelVersion(
    val id: Long,
    val role: String,
    val name: String,
    val baseModel: String,
    val runId: Long?,
    val active: Boolean,
    val metrics: String?,
    val createdAt: Instant,
)

interface ModelVersionRepository {
    suspend fun add(role: String, name: String, baseModel: String, runId: Long?, metrics: String?): ModelVersion
    /** [name]을 켜고 같은 역할의 나머지는 끈다. 기본 모델(목록에 없는 이름)이면 모두 끈다 */
    suspend fun activate(role: String, name: String)
    suspend fun active(role: String): ModelVersion?
    suspend fun list(role: String): List<ModelVersion>
}
