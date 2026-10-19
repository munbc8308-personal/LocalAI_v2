package dev.vesta.server

import dev.vesta.api.ApiJson
import dev.vesta.api.ApprovalDto
import dev.vesta.api.ChatRequestDto
import dev.vesta.api.EventDto
import dev.vesta.api.HealthDto
import dev.vesta.api.MessageDto
import dev.vesta.api.OaiChatRequest
import dev.vesta.api.SessionDto
import dev.vesta.api.toDto
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.tools.ApprovalDecision
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.util.UUID

private val log = KotlinLogging.logger {}

/** 서버가 쓰는 의존성 (테스트에서는 가짜를 넣는다) */
class ServerDeps(
    val conversation: ConversationService,
    val sessions: SessionRepository,
    val messages: MessageRepository,
    val owner: UserContext,
    val token: String,
    val version: String,
    val health: suspend () -> Map<String, String> = { emptyMap() },
)

/**
 * Vesta HTTP API (PROJECT.md I-03, 8장). 이 Mac에서만 열고(기본 127.0.0.1), 모든 요청에 Bearer 토큰을 요구한다.
 * - `/api/…`: Vesta 자체 API. 대화는 SSE로 AgentEvent를 흘려보낸다.
 * - `/v1/…`: OpenAI 호환 (외부 클라이언트용). 이 경로에서는 승인을 받을 수 없으므로 쓰기·셸 도구는 자동 거부한다.
 */
fun Application.vestaApi(deps: ServerDeps) {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error(cause) { "API 오류: ${call.request.local.uri}" }
            call.respondJson(HttpStatusCode.InternalServerError, buildJsonObject { put("error", cause.message ?: "internal error") }.toString())
        }
    }
    routing {
        // 상태만 알려 준다 (토큰 불필요: launchd·CLI가 서버가 떠 있는지 확인)
        get("/api/health") {
            call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(HealthDto.serializer(), HealthDto("ok", deps.version, deps.health())))
        }

        route("/api") {
            post("/chat") {
                if (!call.authorized(deps.token)) return@post
                val request = ApiJson.decodeFromString(ChatRequestDto.serializer(), call.receiveText())
                call.sse { emit ->
                    val channel = if (request.channel == "CLI") ChannelType.CLI else ChannelType.WEB
                    deps.conversation.send(deps.owner, channel, request.sessionId?.let(::SessionId), UserInput(request.text))
                        .collect { emit(ApiJson.encodeToString(EventDto.serializer(), it.toDto())) }
                }
            }
            post("/approvals/{id}") {
                if (!call.authorized(deps.token)) return@post
                val dto = ApiJson.decodeFromString(ApprovalDto.serializer(), call.receiveText())
                val decision = when (dto.decision) {
                    "approve" -> ApprovalDecision.Approve
                    "edit" -> ApprovalDecision.ApproveEdited(kotlinx.serialization.json.JsonObject(dto.edited.mapValues { JsonPrimitive(it.value) }))
                    else -> ApprovalDecision.Deny(dto.reason ?: "사용자가 거부함")
                }
                val ok = deps.conversation.resolveApproval(deps.owner, call.parameters["id"].orEmpty(), decision)
                call.respondJson(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, buildJsonObject { put("resolved", ok) }.toString())
            }
            get("/sessions") {
                if (!call.authorized(deps.token)) return@get
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 20
                val list = deps.sessions.listRecent(deps.owner.userId, limit).map { SessionDto(it.id.value, it.title, it.originChannel.name, it.updatedAt.toString()) }
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(SessionDto.serializer()), list))
            }
            get("/sessions/{id}/messages") {
                if (!call.authorized(deps.token)) return@get
                val id = SessionId(call.parameters["id"].orEmpty())
                if (deps.sessions.find(deps.owner.userId, id) == null) {
                    call.respondJson(HttpStatusCode.NotFound, """{"error":"세션 없음"}""")
                    return@get
                }
                val list = deps.messages.list(id).map { MessageDto(it.role.name, it.content, it.agent?.name, it.model, it.interrupted, it.createdAt.toString()) }
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(MessageDto.serializer()), list))
            }
        }

        route("/v1") {
            get("/models") {
                if (!call.authorized(deps.token)) return@get
                call.respondJson(HttpStatusCode.OK, buildJsonObject {
                    put("object", "list")
                    putJsonArray("data") { add(buildJsonObject { put("id", MODEL_ID); put("object", "model"); put("owned_by", "vesta") }) }
                }.toString())
            }
            post("/chat/completions") {
                if (!call.authorized(deps.token)) return@post
                val request = ApiJson.decodeFromString(OaiChatRequest.serializer(), call.receiveText())
                val question = request.messages.lastOrNull { it.role == "user" }?.content
                    ?: return@post call.respondJson(HttpStatusCode.BadRequest, """{"error":{"message":"user 메시지가 없음"}}""")
                // 같은 대화를 이어 가려면 X-Vesta-Session 헤더 (없으면 새 세션)
                val session = call.request.header("X-Vesta-Session")?.let(::SessionId)
                val id = "chatcmpl-" + UUID.randomUUID().toString().take(12)
                val events = deps.conversation.send(deps.owner, ChannelType.OPENAI_API, session, UserInput(question))
                if (request.stream) {
                    call.sse { emit ->
                        events.collect { event ->
                            when (event) {
                                is AgentEvent.ApprovalRequested -> autoDeny(deps, event)
                                // 스트리밍은 응답 헤더를 이미 보냈으므로 세션 ID를 첫 조각에 싣는다 (OpenAI 클라이언트는 모르는 필드를 무시)
                                is AgentEvent.SessionReady -> emit(chunk(id, null, null, session = event.sessionId.value))
                                is AgentEvent.Token -> emit(chunk(id, event.text, null))
                                is AgentEvent.Done -> emit(chunk(id, null, "stop"))
                                is AgentEvent.Error -> emit(chunk(id, "\n[오류] ${event.message}", "stop"))
                                else -> Unit
                            }
                        }
                        emit("[DONE]")
                    }
                } else {
                    val answer = StringBuilder()
                    var sessionId: String? = null
                    events.collect { event ->
                        when (event) {
                            is AgentEvent.ApprovalRequested -> autoDeny(deps, event)
                            is AgentEvent.SessionReady -> sessionId = event.sessionId.value
                            is AgentEvent.Token -> answer.append(event.text)
                            is AgentEvent.Error -> answer.append("\n[오류] ${event.message}")
                            else -> Unit
                        }
                    }
                    sessionId?.let { call.response.header("X-Vesta-Session", it) }
                    call.respondJson(HttpStatusCode.OK, buildJsonObject {
                        put("id", id)
                        put("object", "chat.completion")
                        put("model", MODEL_ID)
                        putJsonArray("choices") {
                            add(buildJsonObject {
                                put("index", 0)
                                putJsonObject("message") { put("role", "assistant"); put("content", answer.toString()) }
                                put("finish_reason", "stop")
                            })
                        }
                    }.toString())
                }
            }
        }
    }
}

private const val MODEL_ID = "vesta"

/** OpenAI 호환 경로에서는 승인 화면이 없으므로 위험 작업은 거부한다 */
private suspend fun autoDeny(deps: ServerDeps, event: AgentEvent.ApprovalRequested) {
    deps.conversation.resolveApproval(deps.owner, event.requestId, ApprovalDecision.Deny("OpenAI 호환 API에서는 승인할 수 없음 (CLI·웹 UI에서 요청하세요)"))
}

private fun chunk(id: String, content: String?, finish: String?, session: String? = null): String = buildJsonObject {
    put("id", id)
    session?.let { put("vesta_session", it) }
    put("object", "chat.completion.chunk")
    put("model", MODEL_ID)
    put("choices", buildJsonArray {
        add(buildJsonObject {
            put("index", 0)
            putJsonObject("delta") { content?.let { put("content", it) } }
            put("finish_reason", finish?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        })
    })
}.toString()

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
    respondText(body, ContentType.Application.Json, status)

/** `text/event-stream`으로 `data: …` 줄을 흘려보낸다 (POST 요청이라 SSE 플러그인 대신 직접 쓴다) */
private suspend fun ApplicationCall.sse(block: suspend (emit: suspend (String) -> Unit) -> Unit) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    respondTextWriter(ContentType.Text.EventStream) {
        block { data ->
            write("data: $data\n\n")
            flush()
        }
    }
}

/** Bearer 토큰 확인 (시간 차 공격을 피하려고 고정 시간 비교) */
private suspend fun ApplicationCall.authorized(token: String): Boolean {
    val given = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim().orEmpty()
    val ok = MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    if (!ok) respondJson(HttpStatusCode.Unauthorized, """{"error":"토큰이 필요합니다 (Authorization: Bearer …)"}""")
    return ok
}
