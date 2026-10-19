package dev.vesta.api

import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingStage
import dev.vesta.core.knowledge.Evidence
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Vesta API JSON (`/api/…`). 이벤트는 `type` 필드로 구분한다. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
data class ChatRequestDto(val text: String, val sessionId: String? = null, /** CLI / WEB */ val channel: String = "WEB")

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
data class MessageDto(val role: String, val content: String, val agent: String?, val model: String?, val interrupted: Boolean, val createdAt: String)

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
    @Serializable @SerialName("grounding") data class Grounding(val checked: Int, val unsupported: List<String>) : EventDto
    @Serializable @SerialName("token") data class Token(val text: String) : EventDto
    @Serializable @SerialName("done") data class Done(val agent: String, val model: String, val promptTokens: Int?, val completionTokens: Int?) : EventDto
    @Serializable @SerialName("error") data class Error(val message: String) : EventDto
}

fun AgentEvent.toDto(): EventDto = when (this) {
    is AgentEvent.SessionReady -> EventDto.Session(sessionId.value, title, created)
    is AgentEvent.Routed -> EventDto.Routed(decision.toDto())
    is AgentEvent.Status -> EventDto.Status(text)
    is AgentEvent.Reasoning -> EventDto.Reasoning(text)
    is AgentEvent.Sources -> EventDto.Sources(evidence.map { it.toDto() })
    is AgentEvent.ToolStarted -> EventDto.ToolStarted(callId, tool, risk.name, summary)
    is AgentEvent.ToolFinished -> EventDto.ToolFinished(callId, tool, ok, summary)
    is AgentEvent.ApprovalRequested -> EventDto.Approval(
        requestId, tool, risk.name, details.title, details.body, details.warning, reason, editableField, editableValue,
    )
    is AgentEvent.Grounding -> EventDto.Grounding(checked, unsupported)
    is AgentEvent.Token -> EventDto.Token(text)
    is AgentEvent.Done -> EventDto.Done(agent.name, model, usage?.promptTokens, usage?.completionTokens)
    is AgentEvent.Error -> EventDto.Error(message)
}

fun Evidence.toDto() = EvidenceDto(citation, source.name, ref, title, location)

fun RoutingDecision.toDto() = RoutingDto(agent.name, requested?.name, stage.name, confidence, collections, latencyMs)

/** 클라이언트(CLI 원격 모드)가 받은 이벤트를 다시 AgentEvent로 */
fun EventDto.toEvent(): AgentEvent = when (this) {
    is EventDto.Session -> AgentEvent.SessionReady(dev.vesta.core.SessionId(sessionId), title, created)
    is EventDto.Routed -> AgentEvent.Routed(
        RoutingDecision(
            agent = dev.vesta.core.AgentType.valueOf(routing.agent),
            confidence = routing.confidence,
            stage = RoutingStage.valueOf(routing.stage),
            collections = routing.collections,
            question = "",
            latencyMs = routing.latencyMs,
            requested = routing.requested?.let { dev.vesta.core.AgentType.valueOf(it) },
        ),
    )
    is EventDto.Status -> AgentEvent.Status(text)
    is EventDto.Reasoning -> AgentEvent.Reasoning(text)
    is EventDto.Sources -> AgentEvent.Sources(evidence.map {
        Evidence(dev.vesta.core.SourceType.valueOf(it.source), it.ref, it.title, it.location, "", 0.0, it.citation)
    })
    is EventDto.ToolStarted -> AgentEvent.ToolStarted(callId, tool, dev.vesta.core.RiskLevel.valueOf(risk), summary)
    is EventDto.ToolFinished -> AgentEvent.ToolFinished(callId, tool, ok, summary)
    is EventDto.Approval -> AgentEvent.ApprovalRequested(
        requestId, tool, dev.vesta.core.RiskLevel.valueOf(risk), dev.vesta.core.tools.ApprovalDetails(title, body, warning), reason, editableField, editableValue,
    )
    is EventDto.Grounding -> AgentEvent.Grounding(checked, unsupported)
    is EventDto.Token -> AgentEvent.Token(text)
    is EventDto.Done -> AgentEvent.Done(
        dev.vesta.core.AgentType.valueOf(agent), model,
        if (promptTokens != null && completionTokens != null) dev.vesta.core.inference.Usage(promptTokens, completionTokens) else null,
    )
    is EventDto.Error -> AgentEvent.Error(message)
}

// --- OpenAI 호환 API (I-03, 필요한 필드만) ---

@Serializable
data class OaiMessage(val role: String, val content: String? = null)

@Serializable
data class OaiChatRequest(val model: String = "vesta", val messages: List<OaiMessage>, val stream: Boolean = false, val user: String? = null)
