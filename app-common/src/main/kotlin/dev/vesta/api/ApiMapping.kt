package dev.vesta.api

import dev.vesta.core.AgentType
import dev.vesta.core.RiskLevel
import dev.vesta.core.SessionId
import dev.vesta.core.SourceType
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingStage
import dev.vesta.core.inference.Usage
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.tools.ApprovalDetails

// core 타입 ↔ api-model DTO 변환. api-model은 KMP 공통 코드라 core를 모르므로 JVM 쪽(app-common)에 둔다

fun AgentEvent.toDto(): EventDto = when (this) {
    is AgentEvent.SessionReady -> EventDto.Session(sessionId.value, title, created)
    is AgentEvent.Routed -> EventDto.Routed(decision.toDto())
    is AgentEvent.Status -> EventDto.Status(text)
    is AgentEvent.Reasoning -> EventDto.Reasoning(text)
    is AgentEvent.Sources -> EventDto.Sources(evidence.map { it.toDto() })
    is AgentEvent.ToolStarted -> EventDto.ToolStarted(callId, tool, risk.name, summary)
    is AgentEvent.ToolFinished -> EventDto.ToolFinished(callId, tool, ok, summary)
    is AgentEvent.ApprovalRequested -> EventDto.Approval(
        requestId, tool, risk.name, details.title, details.body, details.warning, reason, editableField, editableValue, details.targets,
    )
    is AgentEvent.Grounding -> EventDto.Grounding(checked, unsupported)
    is AgentEvent.Media -> EventDto.Media(asset.id, asset.mime, caption)
    is AgentEvent.Saved -> EventDto.Saved(messageId)
    is AgentEvent.Token -> EventDto.Token(text)
    is AgentEvent.Done -> EventDto.Done(agent.name, model, usage?.promptTokens, usage?.completionTokens)
    is AgentEvent.Error -> EventDto.Error(message)
}

fun Evidence.toDto() = EvidenceDto(citation, source.name, ref, title, location)

fun RoutingDecision.toDto() = RoutingDto(agent.name, requested?.name, stage.name, confidence, collections, latencyMs)

/** 클라이언트(CLI 원격 모드)가 받은 이벤트를 다시 AgentEvent로 */
fun EventDto.toEvent(): AgentEvent = when (this) {
    is EventDto.Session -> AgentEvent.SessionReady(SessionId(sessionId), title, created)
    is EventDto.Routed -> AgentEvent.Routed(
        RoutingDecision(
            agent = AgentType.valueOf(routing.agent),
            confidence = routing.confidence,
            stage = RoutingStage.valueOf(routing.stage),
            collections = routing.collections,
            question = "",
            latencyMs = routing.latencyMs,
            requested = routing.requested?.let { AgentType.valueOf(it) },
        ),
    )
    is EventDto.Status -> AgentEvent.Status(text)
    is EventDto.Reasoning -> AgentEvent.Reasoning(text)
    is EventDto.Sources -> AgentEvent.Sources(evidence.map {
        Evidence(SourceType.valueOf(it.source), it.ref, it.title, it.location, "", 0.0, it.citation)
    })
    is EventDto.ToolStarted -> AgentEvent.ToolStarted(callId, tool, RiskLevel.valueOf(risk), summary)
    is EventDto.ToolFinished -> AgentEvent.ToolFinished(callId, tool, ok, summary)
    is EventDto.Approval -> AgentEvent.ApprovalRequested(
        requestId, tool, RiskLevel.valueOf(risk), ApprovalDetails(title, body, warning, targets), reason, editableField, editableValue,
    )
    is EventDto.Grounding -> AgentEvent.Grounding(checked, unsupported)
    // 원격 모드: 내용은 받지 않았다 (필요하면 /api/media/{id}로 받는다)
    is EventDto.Media -> AgentEvent.Media(
        dev.vesta.core.media.MediaAsset(id, dev.vesta.core.UserId(0), dev.vesta.core.media.MediaKind.GENERATED, mime, 0, caption),
        ByteArray(0),
        caption,
    )
    is EventDto.Saved -> AgentEvent.Saved(messageId)
    is EventDto.Token -> AgentEvent.Token(text)
    is EventDto.Done -> AgentEvent.Done(
        AgentType.valueOf(agent), model,
        promptTokens?.let { p -> completionTokens?.let { c -> Usage(p, c) } },
    )
    is EventDto.Error -> AgentEvent.Error(message)
}
