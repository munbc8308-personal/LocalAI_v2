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
)

/** 메시지에 붙인 미디어. 내용은 `GET /api/media/{id}` */
@Serializable
data class AttachmentDto(val id: String, val mime: String)

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
    val role: String,
    val content: String,
    val agent: String?,
    val model: String?,
    val interrupted: Boolean,
    val createdAt: String,
    val attachments: List<AttachmentDto> = emptyList(),
)

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
    ) : EventDto
    /** 에이전트가 만든 이미지. 내용은 `GET /api/media/{id}` */
    @Serializable @SerialName("media") data class Media(val id: String, val mime: String, val caption: String?) : EventDto
    @Serializable @SerialName("grounding") data class Grounding(val checked: Int, val unsupported: List<String>) : EventDto
    @Serializable @SerialName("token") data class Token(val text: String) : EventDto
    @Serializable @SerialName("done") data class Done(val agent: String, val model: String, val promptTokens: Int?, val completionTokens: Int?) : EventDto
    @Serializable @SerialName("error") data class Error(val message: String) : EventDto
}

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

/** 쿠키 인증 POST 요청에 붙이는 CSRF 헤더 */
const val CSRF_HEADER = "X-Vesta-CSRF"

// --- OpenAI 호환 API (I-03, 필요한 필드만) ---

@Serializable
data class OaiMessage(val role: String, val content: String? = null)

@Serializable
data class OaiChatRequest(val model: String = "vesta", val messages: List<OaiMessage>, val stream: Boolean = false, val user: String? = null)
