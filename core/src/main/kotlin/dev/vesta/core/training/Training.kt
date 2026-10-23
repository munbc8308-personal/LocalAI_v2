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
    /** [userId]가 null이면 모든 사용자. 학습에는 동의한 사용자(L-09)별로 불러 쓴다 */
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

/** 학습 재료 종류 (L-25): 라우팅(질문 → 담당), 답 좋고 나쁨, 도구 사용, 검색(질문 ↔ 정답 조각, L-23) */
enum class SampleKind { ROUTING, ANSWER, TOOL, RETRIEVAL }

/** 재료를 찾은 신호 (L-20·L-21) */
enum class SampleSignal {
    /** 👍·👎 (웹·메신저 반응) */
    FEEDBACK,
    /** 같은 질문을 담당을 지정해 다시 물음 (`/web …`) */
    REASK,
    /** 같은 질문을 말만 바꿔 다시 물음 (앞 답이 나빴다) */
    REPHRASE,
    /** "아니 그게 아니라" 같은 바로잡음 */
    CORRECTION,
    /** 도구 승인 거부 */
    DENIAL,
    /** 메인 모델이 조각을 보고 만든 질문 (L-23) */
    SYNTHETIC,
}

/** 검토 상태 (L-26): 확실한 것만 바로 쓰고, 나머지는 주인이 본다 */
enum class SampleStatus { PENDING, ACCEPTED, REJECTED }

/**
 * 학습 재료 하나 (L-25). [label]: ROUTING은 담당 에이전트 이름, ANSWER는 good·bad, TOOL은 denied:<도구>,
 * RETRIEVAL은 어려운 오답 조각 ID(쉼표로, 없으면 "-")이고 [messageId]가 정답 조각 ID, [answer]는 그 조각 글(검토용).
 * [question]·[answer]는 개인정보를 지운 글. [confidence]는 신호의 확실함(0~1)
 */
data class TrainingSample(
    val id: Long,
    val userId: UserId,
    val kind: SampleKind,
    val signal: SampleSignal,
    val messageId: Long?,
    val question: String,
    val answer: String?,
    val label: String,
    val confidence: Double,
    val status: SampleStatus,
    val note: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val reviewedAt: Instant? = null,
)

interface TrainingSampleRepository {
    /** 같은 종류·질문·라벨이 이미 있으면 넣지 않고 null */
    suspend fun add(sample: TrainingSample): TrainingSample?
    suspend fun list(status: SampleStatus?, limit: Int): List<TrainingSample>
    /** 주인 검토: 맞음(ACCEPTED)·틀림(REJECTED), 고치기는 [label]을 바꿔 ACCEPTED */
    suspend fun review(id: Long, status: SampleStatus, label: String? = null): Boolean
    /** 종류·상태별 개수 ("ROUTING:PENDING" → 3) */
    suspend fun counts(): Map<String, Int>
    /** 학습에 쓸 재료: [kind]의 ACCEPTED, [users]의 것만 (동의한 사용자, L-09) */
    suspend fun accepted(kind: SampleKind, users: Set<UserId>): List<TrainingSample>
    /** [kind] 재료가 가리키는 [TrainingSample.messageId]들 (상태 무관). 검색 재료는 이미 질문을 만든 조각을 다시 보지 않게 */
    suspend fun messageIds(kind: SampleKind): Set<Long> = list(null, 100_000).filter { it.kind == kind }.mapNotNull { it.messageId }.toSet()
    /** 큐레이터가 어디까지 봤는지 (메시지 ID) */
    suspend fun cursor(): Long
    suspend fun setCursor(messageId: Long)
}

/**
 * 큐레이터가 보는 대화 한 턴 (L-25): 질문과 그 답, 그때의 라우팅·피드백·거부한 도구.
 * 같은 세션의 턴은 시간 순으로 온다
 */
data class CuratorTurn(
    val userId: UserId,
    val sessionId: String,
    val questionId: Long,
    val question: String,
    val questionAt: Instant,
    val answerId: Long? = null,
    val answer: String? = null,
    /** 실제로 답한 에이전트 */
    val answerAgent: String? = null,
    /** 라우터 판정 단계 (EXPLICIT·RULE·DECISION_MODEL…) */
    val routedStage: String? = null,
    val rating: Int? = null,
    val correctAgent: String? = null,
    val deniedTools: List<String> = emptyList(),
)

interface CuratorSource {
    /** [afterMessageId] 뒤에 새 질문이 있는 세션의 턴들 (그 앞 턴 하나 포함, 신호를 찾으려면 앞 턴이 필요하다). [users]의 것만 */
    suspend fun turns(afterMessageId: Long, users: Set<UserId>, limit: Int): List<CuratorTurn>
}

/**
 * D6 코드 근거 충분도 재료를 만들 검색 결과 (PROJECT.md L-22): CodeAgent와 같은 검색·자료 묶기로 결정 모델이 실제로 볼 글(앞부분만)과
 * 그 글에 들어간 파일 경로
 */
data class CodeContext(val text: String, val files: List<String>)

/**
 * 검색 재료(L-23)를 만들 조각: 동의한 사용자(L-09)가 가진 컬렉션의 것. [passage]는 색인할 때 임베딩에 넣은 글과 같은 꼴(제목 > 구역 + 본문)
 */
data class Passage(
    val chunkId: Long,
    val userId: UserId,
    val collectionId: Long,
    val embeddingModel: String,
    val path: String,
    val heading: String?,
    val text: String,
    val passage: String,
    val code: Boolean,
)

interface PassageSource {
    /** 아직 재료로 쓰지 않은 조각 [limit]개 (컬렉션을 고르게 섞어서) */
    suspend fun sample(users: Set<UserId>, exclude: Set<Long>, limit: Int): List<Passage>
    /** 지금 임베딩으로 [question]을 [of]가 있는 컬렉션에서 벡터 검색한 조각, 가까운 것부터 */
    suspend fun nearest(of: Passage, question: String, limit: Int): List<Passage>
    suspend fun byIds(ids: List<Long>): List<Passage>
    /** 학습·평가에 쓸 조각 전부: [users]의 컬렉션 중 임베딩 모델이 [model]인 것 */
    suspend fun corpus(users: Set<UserId>, model: String): List<Passage>
}
