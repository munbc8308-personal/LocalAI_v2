package dev.vesta.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 서버(JVM)·CLI·웹 UI(JS)가 함께 쓰는 API 형식 (PROJECT.md 1.4, 10장). Kotlin Multiplatform 공통 코드라 JVM 전용 타입을 쓰지 않는다.
 * core 타입과의 변환은 app-common `ApiMapping.kt`에 있다.
 */

/** Vesta API JSON (`/api/…`). 이벤트는 `type` 필드로 구분한다. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
data class ChatRequestDto(
    val text: String,
    val sessionId: String? = null,
    /** CLI / WEB */
    val channel: String = "WEB",
    /** 질문에 붙인 이미지 (base64, PNG·JPEG·WebP·GIF). 형식은 서버가 파일 앞부분으로 확인한다 (V-04) */
    val images: List<String> = emptyList(),
    /** CLI 작업 폴더(절대 경로)와 그 폴더의 VESTA.md 내용 (M17). CLI 요청의 Mac 주인 것만 쓴다 */
    val workspace: String? = null,
    val instructions: String? = null,
    /** 작업 폴더의 스킬 파일(`.vesta/skills/<폴더>/SKILL.md` 내용, K-01). CLI가 사용자 승인을 받은 것만 보낸다 */
    val skills: List<ProjectSkillDto> = emptyList(),
    /** 답을 소리로 들려준다 (웹 음성 대화, M19): 짧게 말하듯이 */
    val spoken: Boolean = false,
)

@Serializable
data class ProjectSkillDto(val folder: String, val text: String)

/** 스킬 (K-01). [source]: PROJECT·PERSONAL·WEB. 웹에서 만든 것만 고칠 수 있다 */
@Serializable
data class SkillDto(
    val name: String,
    val description: String,
    val body: String,
    val agent: String = "CHAT",
    val tools: List<String> = emptyList(),
    val source: String = "WEB",
)

/** 메시지에 붙인 미디어. 내용은 `GET /api/media/{id}` */
@Serializable
data class AttachmentDto(val id: String, val mime: String)

/** 학습 실행 (L-05). [report]는 평가 게이트 결과 JSON */
@Serializable
data class TrainingRunDto(
    val id: Long,
    val kind: String,
    /** RUNNING / PASSED / REJECTED / FAILED / CANCELLED */
    val status: String,
    val stage: String?,
    val step: Int,
    val total: Int,
    val loss: Double?,
    val candidate: String?,
    val error: String?,
    val report: String?,
    val startedAt: String,
    val finishedAt: String?,
)

@Serializable
data class ModelVersionDto(val name: String, val baseModel: String, val active: Boolean, val runId: Long?, val createdAt: String)

/** 학습 화면 (`GET /api/training`) */
@Serializable
data class TrainingStateDto(
    /** 지금 쓰는 결정 모델 */
    val decisionModel: String,
    /** 학습 출발점이자 되돌릴 때 쓰는 기본 모델 */
    val baseModel: String,
    /** 다음 학습에 쓸 사용자 라우팅 정정 수 */
    val corrections: Int,
    val runs: List<TrainingRunDto>,
    val versions: List<ModelVersionDto>,
    /** 학습 재료 개수: "ROUTING:PENDING" → 3 (L-25) */
    val samples: Map<String, Int> = emptyMap(),
    /** 지금 쓰는 임베딩 모델과 출발점 (L-23) */
    val embeddingModel: String? = null,
    val embeddingBase: String? = null,
    val embeddingVersions: List<ModelVersionDto> = emptyList(),
    /** 메인 모델 학습 (L-24): 쓸 수 있는 재료(👍 받은 일반 대화 답)·최소 수·학습한 후보 */
    val generalSamples: Int = 0,
    val generalMinimum: Int = 0,
    val generalVersions: List<ModelVersionDto> = emptyList(),
)

/** 학습 재료 하나 (L-25·L-26 검토) */
@Serializable
data class TrainingSampleDto(
    val id: Long,
    val user: String?,
    val kind: String,
    val signal: String,
    val question: String,
    val answer: String?,
    val label: String,
    val confidence: Double,
    val status: String,
    val note: String?,
    val createdAt: String,
)

/** 검토: status ACCEPTED·REJECTED, 고치면 [label]도 */
@Serializable
data class SampleReviewDto(val status: String, val label: String? = null)

@Serializable
/** [role]: decision(결정 모델) 또는 embedding(임베딩, L-23) */
data class ActivateDto(val name: String, val role: String = "decision")

/** 주간 점검 한 번 (M16, E-01). [report]는 마크다운 요약 */
@Serializable
data class EvolutionRunDto(
    val id: Long,
    /** RUNNING / DONE / FAILED */
    val status: String,
    val stage: String?,
    val report: String?,
    val error: String?,
    val startedAt: String,
    val finishedAt: String?,
)

/** 설정 변경안 (E-05). [kind] MODEL(승인 필요) / TUNING(바로 반영), [status] PENDING / APPLIED / REJECTED / ROLLED_BACK / EXPIRED */
@Serializable
data class ProposalDto(
    val id: Long,
    val kind: String,
    val title: String,
    val summary: String,
    val changes: Map<String, String>,
    val previous: Map<String, String?>,
    val status: String,
    val createdAt: String,
)

/** 자가 개선 화면 (`GET /api/evolution`) */
@Serializable
data class EvolutionStateDto(
    val enabled: Boolean,
    val lastRun: EvolutionRunDto?,
    val proposals: List<ProposalDto>,
    /** 지금 덮어쓴 설정 (`~/.vesta/tuned.properties`) */
    val tuned: Map<String, String>,
    /** 반영한 설정을 쓰려고 서버가 다시 시작하기를 기다리는 중 */
    val restartPending: Boolean,
)

/** 제안 승인·거절·되돌리기 결과 */
@Serializable
data class ProposalResultDto(val ok: Boolean, val message: String)

/** 갤러리 항목 (`GET /api/media?kind=GENERATED`) */
@Serializable
data class MediaItemDto(val id: String, val mime: String, val caption: String?, val createdAt: String?)

/** 음성 인식 결과 (`POST /api/stt`) */
@Serializable
data class TranscriptDto(val text: String)

/** 읽어 주기 요청 (`POST /api/speech`, 응답은 audio/mp4) */
@Serializable
data class SpeechRequestDto(val text: String)

@Serializable
data class ApprovalDto(
    /** approve / deny / edit */
    val decision: String,
    val reason: String? = null,
    /** decision=edit일 때 바꾼 인자 (셸이면 {"command": "…"}) */
    val edited: Map<String, String> = emptyMap(),
)

@Serializable
data class SessionDto(val id: String, val title: String?, val channel: String, val updatedAt: String)

@Serializable
data class MessageDto(
    val id: Long = 0,
    val role: String,
    val content: String,
    val agent: String?,
    val model: String?,
    val interrupted: Boolean,
    val createdAt: String,
    val attachments: List<AttachmentDto> = emptyList(),
    /** 남긴 피드백 (1 좋음, -1 나쁨) */
    val rating: Int? = null,
    val correctAgent: String? = null,
)

/** 답변 피드백 (`POST /api/messages/{id}/feedback`, C-09). [correctAgent]는 라우팅 정정 (CHAT·DOCS·WEB…) */
@Serializable
data class FeedbackDto(val rating: Int = 0, val correctAgent: String? = null, val comment: String? = null)

@Serializable
data class HealthDto(val status: String, val version: String, val checks: Map<String, String>)

@Serializable
data class EvidenceDto(val citation: Int?, val source: String, val ref: String, val title: String, val location: String?)

@Serializable
data class RoutingDto(
    val agent: String,
    val requested: String?,
    val stage: String,
    val confidence: Double,
    val collections: List<String>,
    val latencyMs: Long,
    /** 사용자 정의 에이전트 이름 (M22) */
    val customAgent: String? = null,
    /** [customAgent]가 팀 이름 (M22) */
    val team: Boolean = false,
    /** 스킬로 부른 경우 그 이름 (M22) */
    val skill: String? = null,
)

/** SSE로 흘려보내는 대화 이벤트 (AgentEvent의 직렬화 형태) */
@Serializable
sealed interface EventDto {
    @Serializable @SerialName("session") data class Session(val sessionId: String, val title: String?, val created: Boolean) : EventDto
    @Serializable @SerialName("routed") data class Routed(val routing: RoutingDto) : EventDto
    @Serializable @SerialName("status") data class Status(val text: String) : EventDto
    @Serializable @SerialName("reasoning") data class Reasoning(val text: String) : EventDto
    @Serializable @SerialName("sources") data class Sources(val evidence: List<EvidenceDto>) : EventDto
    @Serializable @SerialName("tool_started") data class ToolStarted(val callId: String, val tool: String, val risk: String, val summary: String) : EventDto
    @Serializable @SerialName("tool_finished") data class ToolFinished(val callId: String, val tool: String, val ok: Boolean, val summary: String) : EventDto
    @Serializable @SerialName("approval") data class Approval(
        val requestId: String, val tool: String, val risk: String, val title: String, val body: String,
        val warning: String?, val reason: String?, val editableField: String?, val editableValue: String?,
        /** 쓰는 실제 경로들 (내장 파일 도구만, M17) */
        val targets: List<String> = emptyList(),
    ) : EventDto
    /** 답변을 저장했다 (피드백용 메시지 ID) */
    @Serializable @SerialName("saved") data class Saved(val messageId: Long) : EventDto
    /** 에이전트가 만든 이미지. 내용은 `GET /api/media/{id}` */
    @Serializable @SerialName("media") data class Media(val id: String, val mime: String, val caption: String?) : EventDto
    /** 오래 걸리는 일을 백그라운드로 넘겼다 (M24). 진행은 `GET /api/jobs/{id}` */
    @Serializable @SerialName("job") data class Job(val id: String, val title: String) : EventDto
    @Serializable @SerialName("grounding") data class Grounding(val checked: Int, val unsupported: List<String>) : EventDto
    @Serializable @SerialName("token") data class Token(val text: String) : EventDto
    @Serializable @SerialName("done") data class Done(val agent: String, val model: String, val promptTokens: Int?, val completionTokens: Int?) : EventDto
    @Serializable @SerialName("error") data class Error(val message: String) : EventDto
}

/** 백그라운드 작업 상태 (M24): QUEUED·RUNNING·DONE·FAILED. 끝나면 [mediaId]·[text] */
@Serializable
data class JobDto(
    val id: String,
    val title: String,
    val status: String,
    val progress: String? = null,
    val mediaId: String? = null,
    val mime: String? = null,
    val text: String? = null,
)

// --- 웹 UI (M9) ---

@Serializable
data class LoginDto(val password: String, /** 없으면 OWNER */ val name: String? = null)

/** 로그인한 사용자. [csrf]는 쿠키로 인증한 POST 요청의 `X-Vesta-CSRF` 헤더 값 */
@Serializable
data class MeDto(val name: String, val role: String, val csrf: String)

@Serializable
data class CollectionDto(
    val name: String,
    /** DOCS / CODE */
    val kind: String,
    val description: String?,
    val rootPath: String?,
    val embeddingModel: String,
    val documents: Long,
    val chunks: Long,
    /** 다른 사용자가 공유해 준 컬렉션이면 그 사용자 이름 (U-04) */
    val owner: String? = null,
    /** 내 컬렉션을 공유한 사용자 이름 */
    val sharedWith: List<String> = emptyList(),
)

@Serializable
data class LoadedModelDto(val name: String, val sizeBytes: Long, val gpuBytes: Long)

@Serializable
data class DashboardDto(
    val version: String,
    /** 엔진·서비스 이름 → 상태 설명 */
    val checks: Map<String, String>,
    /** 역할(general, code, embedding, decision…) → 모델 이름 */
    val models: Map<String, String>,
    val loaded: List<LoadedModelDto>,
    val memoryTotalBytes: Long,
    val memoryBudgetBytes: Long,
)

/** 사용자 (U-01). 오늘 사용량은 이 Mac 시간대 자정부터 (U-05) */
@Serializable
data class UserDto(
    val name: String,
    val role: String,
    val hasPassword: Boolean,
    val trainingConsent: Boolean,
    val questionsToday: Int,
    val tokensToday: Long,
    val createdAt: String,
)

/** 새 사용자. 비밀번호가 없으면 로그인할 수 없다 (나중에 정한다) */
@Serializable
data class NewUserDto(val name: String, val role: String, val password: String? = null)

@Serializable
data class RoleDto(val role: String)

@Serializable
data class PasswordDto(val password: String)

@Serializable
data class ConsentDto(val consent: Boolean)

/** 본인 비밀번호 바꾸기 */
@Serializable
data class ChangePasswordDto(val current: String, val new: String)

/** 쿠키 인증 POST 요청에 붙이는 CSRF 헤더 */
const val CSRF_HEADER = "X-Vesta-CSRF"

// --- OpenAI 호환 API (I-03, 필요한 필드만) ---

@Serializable
data class OaiMessage(val role: String, val content: String? = null)

@Serializable
data class OaiChatRequest(val model: String = "vesta", val messages: List<OaiMessage>, val stream: Boolean = false, val user: String? = null)

// --- 메신저 연결 (M20, U-02) ---

/** 연결된 메신저 계정 하나. [user]는 연결된 Vesta 사용자 이름 */
@Serializable
data class MessengerLinkDto(val channel: String, val externalId: String, val user: String)

/** 메신저 연결 화면 (`GET /api/me/messengers`, OWNER는 `GET /api/messengers`) */
@Serializable
data class MessengersDto(
    /** 지금 켜진 메신저 (TELEGRAM / DISCORD / SLACK) */
    val enabled: List<String>,
    val links: List<MessengerLinkDto>,
)

/** 일회용 연결 코드. [how]는 그 메신저에서 코드를 보내는 방법 */
@Serializable
data class PairCodeDto(val channel: String, val code: String, val expiresInSeconds: Long, val how: String)

/** 사용자별 설정 (U-07, M20). [tone]: DEFAULT / FORMAL(존댓말) / CASUAL(반말). [voices]는 고를 수 있는 목소리 (읽기 전용) */
@Serializable
data class SettingsDto(
    val nickname: String? = null,
    val tone: String = "DEFAULT",
    val voiceReply: Boolean? = null,
    val voice: String? = null,
    val voices: List<String> = emptyList(),
)

// --- 에이전트 스튜디오 (M22) ---

/** 사용자 정의 에이전트. [model]: GENERAL / CODE, [tools]: 쓸 도구 이름 */
@Serializable
data class CustomAgentDto(
    val name: String,
    val description: String,
    val instructions: String,
    val model: String = "GENERAL",
    val tools: List<String> = emptyList(),
    val updatedAt: String? = null,
)

@Serializable
data class ToolInfoDto(val name: String, val risk: String, val description: String)

/** 기본 에이전트 소개 */
@Serializable
data class BuiltinAgentDto(val name: String, val label: String, val description: String)

/** 에이전트 탭 (`GET /api/agents`) */
@Serializable
data class AgentsDto(val builtin: List<BuiltinAgentDto>, val custom: List<CustomAgentDto>, val tools: List<ToolInfoDto>, val teams: List<TeamDto> = emptyList(),
    val a2a: List<A2aAgentDto> = emptyList(),
    /** A2A 에이전트를 등록·쓸 수 있는가 (Mac 주인만) */
    val canA2a: Boolean = false,
    val skills: List<SkillDto> = emptyList(),
)

/** 등록한 A2A 에이전트 (A-15) */
@Serializable
data class A2aAgentDto(
    val name: String,
    val displayName: String,
    val description: String,
    val endpoint: String,
    val protocol: String,
    val streaming: Boolean,
    val auth: Boolean,
    /** 내부 에이전트 (이 Mac·내 네트워크) */
    val local: Boolean = false,
    /** 키체인에 토큰이 있는가 */
    val hasToken: Boolean,
    val skills: List<String>,
    val cardUrl: String,
)

/** A2A 등록 요청 (`POST /api/a2a/preview`, `PUT /api/a2a/{name}`) */
@Serializable
data class A2aAddDto(
    val url: String,
    /** 미리 보기에서 확인한 연결 주소 (등록할 때 카드가 바뀌었으면 거부) */
    val endpoint: String? = null,
    /** 이 Mac·내 네트워크(Tailscale)의 에이전트라고 사용자가 직접 고름: 내부 주소에 연결하되 카드와 같은 출처만 */
    val local: Boolean = false,
)

/** 등록 전에 보여 줄 Agent Card 내용과 경고 */
@Serializable
data class A2aPreviewDto(
    val suggestedName: String,
    val displayName: String,
    val description: String,
    val endpoint: String,
    val protocol: String,
    val streaming: Boolean,
    val auth: Boolean,
    val skills: List<String>,
    val version: String?,
    val warnings: List<String>,
)

/** 팀 (A-14): 리더·멤버는 사용자 정의 에이전트 이름 (`PUT /api/teams/{name}`) */
@Serializable
data class TeamDto(val name: String, val description: String, val leader: String, val members: List<String>, val updatedAt: String? = null)

@Serializable
data class RunDto(
    val id: Long,
    val agent: String,
    val customAgent: String?,
    val input: String,
    /** RUNNING / DONE / FAILED / CANCELLED */
    val status: String,
    val startedAt: String,
    val finishedAt: String?,
    val sessionId: String?,
)

@Serializable
data class RunEventDto(val run: Long, val seq: Int, val at: String, val kind: String, val title: String, val detail: String?)

@Serializable
data class RunDetailDto(val run: RunDto, val events: List<RunEventDto>, val answer: String?)

/** 실시간 소식 (`GET /api/runs/stream`, SSE): 새 단계([event]) 또는 실행 상태([run]) */
@Serializable
data class RunUpdateDto(val run: RunDto? = null, val event: RunEventDto? = null)
