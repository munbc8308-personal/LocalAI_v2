package dev.vesta.server

import dev.vesta.api.ApiJson
import dev.vesta.api.ApprovalDto
import dev.vesta.api.AttachmentDto
import dev.vesta.api.SpeechRequestDto
import dev.vesta.api.TranscriptDto
import dev.vesta.api.CSRF_HEADER
import dev.vesta.api.ChatRequestDto
import dev.vesta.api.CollectionDto
import dev.vesta.api.DashboardDto
import dev.vesta.api.EventDto
import dev.vesta.api.HealthDto
import dev.vesta.api.LoginDto
import dev.vesta.api.MeDto
import dev.vesta.api.MessageDto
import dev.vesta.api.OaiChatRequest
import dev.vesta.api.SessionDto
import dev.vesta.api.toDto
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.config.MediaConfig
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.ImageInput
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.MediaStore
import dev.vesta.core.media.SpeechToText
import dev.vesta.core.media.TextToSpeech
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.tools.ApprovalDecision
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
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
    /** 웹 UI 로그인. 없으면 Bearer 토큰만 받는다 */
    val auth: WebAuth? = null,
    val collections: suspend (UserContext) -> List<CollectionDto> = { emptyList() },
    val dashboard: suspend () -> DashboardDto = { DashboardDto(version, emptyMap(), emptyMap(), emptyList(), 0, 0) },
    /** 접속을 받는 Host 이름 (DNS 리바인딩 방지). localhost·127.0.0.1·Tailscale(`*.ts.net`)은 항상 허용 */
    val allowedHosts: Set<String> = emptySet(),
    /** 웹 UI 정적 파일(클래스패스 `web/`)을 서빙할지 */
    val serveWebUi: Boolean = false,
    /** 음성·이미지 (M12). 없으면 해당 API는 503 */
    val speechToText: SpeechToText? = null,
    val textToSpeech: TextToSpeech? = null,
    val media: MediaStore? = null,
    val mediaLimits: MediaConfig = MediaConfig(),
)

/**
 * Vesta HTTP API (PROJECT.md I-03, 8장). 이 Mac에서만 열고(기본 127.0.0.1), 인증이 필요하다.
 * - `/api/…`: Vesta 자체 API. 대화는 SSE로 AgentEvent를 흘려보낸다.
 *   CLI는 Bearer 토큰, 웹 UI는 로그인 세션 쿠키 + CSRF 헤더로 인증한다.
 * - `/v1/…`: OpenAI 호환 (외부 클라이언트용, Bearer 토큰만). 이 경로에서는 승인을 받을 수 없으므로 쓰기·셸 도구는 자동 거부한다.
 * - `/`: 웹 UI (M9).
 */
fun Application.vestaApi(deps: ServerDeps) {
    // 웹 UI 파일을 휴대폰으로 보낼 때 크기를 줄인다. SSE는 조각마다 바로 보내야 하므로 압축하지 않는다
    install(Compression) {
        gzip { excludeContentType(ContentType.Text.EventStream) }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error(cause) { "API 오류: ${call.request.local.uri}" }
            call.respondJson(HttpStatusCode.InternalServerError, buildJsonObject { put("error", cause.message ?: "internal error") }.toString())
        }
    }
    // 모든 응답: Host 검사(DNS 리바인딩 방지)와 보안 헤더
    intercept(ApplicationCallPipeline.Plugins) {
        if (!hostAllowed(call.request.host(), deps.allowedHosts)) {
            call.respondJson(HttpStatusCode.Forbidden, """{"error":"허용되지 않은 Host"}""")
            finish()
            return@intercept
        }
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
    }
    routing {
        // 상태만 알려 준다 (인증 불필요: launchd·CLI가 서버가 떠 있는지 확인)
        get("/api/health") {
            call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(HealthDto.serializer(), HealthDto("ok", deps.version, deps.health())))
        }

        route("/api") {
            post("/login") {
                val auth = deps.auth ?: return@post call.respondJson(HttpStatusCode.NotFound, """{"error":"웹 로그인이 꺼져 있음"}""")
                val dto = ApiJson.decodeFromString(LoginDto.serializer(), call.receiveText())
                when (val result = auth.login(dto.name, dto.password, call.request.header(HttpHeaders.UserAgent))) {
                    is LoginResult.Ok -> {
                        call.response.cookies.append(
                            Cookie(
                                SESSION_COOKIE, result.token,
                                maxAge = auth.sessionTtl.seconds.toInt(), path = "/", httpOnly = true,
                                secure = call.isHttps(), extensions = mapOf("SameSite" to "Strict"),
                            ),
                        )
                        call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(MeDto.serializer(), result.session.toMe()))
                    }
                    LoginResult.Invalid -> call.respondJson(HttpStatusCode.Unauthorized, """{"error":"비밀번호가 맞지 않습니다"}""")
                    LoginResult.NoPassword -> call.respondJson(HttpStatusCode.Conflict, """{"error":"비밀번호가 아직 없습니다. 이 Mac 터미널에서 vesta passwd 로 정하세요"}""")
                    is LoginResult.Locked -> {
                        call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                        call.respondJson(HttpStatusCode.TooManyRequests, """{"error":"여러 번 틀려 잠시 잠겼습니다. ${result.retryAfterSeconds}초 뒤에 다시 시도하세요"}""")
                    }
                }
            }
            post("/logout") {
                call.principal(deps) ?: return@post
                call.request.cookies[SESSION_COOKIE]?.let { deps.auth?.logout(it) }
                call.response.cookies.append(Cookie(SESSION_COOKIE, "", maxAge = 0, path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Strict")))
                call.respondJson(HttpStatusCode.OK, """{"ok":true}""")
            }
            get("/me") {
                val session = call.request.cookies[SESSION_COOKIE]?.let { deps.auth?.session(it) }
                    ?: return@get call.respondJson(HttpStatusCode.Unauthorized, """{"error":"로그인이 필요합니다"}""")
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(MeDto.serializer(), session.toMe()))
            }
            post("/chat") {
                val user = call.principal(deps) ?: return@post
                val request = ApiJson.decodeFromString(ChatRequestDto.serializer(), call.receiveText())
                val images = when (val decoded = decodeImages(request.images, deps.mediaLimits)) {
                    is ImagesResult.Ok -> decoded.images
                    is ImagesResult.Invalid -> return@post call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", decoded.message) }.toString())
                }
                call.sse { emit ->
                    val channel = if (request.channel == "CLI") ChannelType.CLI else ChannelType.WEB
                    deps.conversation.send(user, channel, request.sessionId?.let(::SessionId), UserInput(request.text, images))
                        .collect { emit(ApiJson.encodeToString(EventDto.serializer(), it.toDto())) }
                }
            }
            post("/approvals/{id}") {
                val user = call.principal(deps) ?: return@post
                val dto = ApiJson.decodeFromString(ApprovalDto.serializer(), call.receiveText())
                val decision = when (dto.decision) {
                    "approve" -> ApprovalDecision.Approve
                    "edit" -> ApprovalDecision.ApproveEdited(kotlinx.serialization.json.JsonObject(dto.edited.mapValues { JsonPrimitive(it.value) }))
                    else -> ApprovalDecision.Deny(dto.reason ?: "사용자가 거부함")
                }
                val ok = deps.conversation.resolveApproval(user, call.parameters["id"].orEmpty(), decision)
                call.respondJson(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, buildJsonObject { put("resolved", ok) }.toString())
            }
            get("/sessions") {
                val user = call.principal(deps) ?: return@get
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 20
                val list = deps.sessions.listRecent(user.userId, limit).map { SessionDto(it.id.value, it.title, it.originChannel.name, it.updatedAt.toString()) }
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(SessionDto.serializer()), list))
            }
            get("/sessions/{id}/messages") {
                val user = call.principal(deps) ?: return@get
                val id = SessionId(call.parameters["id"].orEmpty())
                if (deps.sessions.find(user.userId, id) == null) {
                    call.respondJson(HttpStatusCode.NotFound, """{"error":"세션 없음"}""")
                    return@get
                }
                val list = deps.messages.list(id).map {
                    MessageDto(it.role.name, it.content, it.agent?.name, it.model, it.interrupted, it.createdAt.toString(), it.attachments.map { a -> AttachmentDto(a.id, a.mime) })
                }
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(MessageDto.serializer()), list))
            }
            // 음성 인식 (V-01): 본문은 녹음 파일 그대로 (webm·ogg·m4a·wav). 인식한 글만 돌려주고, 질문은 사용자가 확인한 뒤 보낸다 (V-02)
            post("/stt") {
                val user = call.principal(deps) ?: return@post
                val stt = deps.speechToText ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"음성 인식이 설정돼 있지 않습니다 (models.stt)"}""")
                val audio = call.receiveLimited(deps.mediaLimits.maxAudioMegabytes) ?: return@post
                try {
                    val text = stt.transcribe(audio)
                    log.info { "음성 인식 ${audio.size} bytes → ${text.length}자 (user ${user.userId.value})" }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(TranscriptDto.serializer(), TranscriptDto(text)))
                } catch (e: MediaException) {
                    call.respondJson(HttpStatusCode.UnprocessableEntity, buildJsonObject { put("error", e.message.orEmpty()) }.toString())
                }
            }
            // 읽어 주기 (V-03): audio/mp4
            post("/speech") {
                call.principal(deps) ?: return@post
                val tts = deps.textToSpeech ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"읽어 주기가 설정돼 있지 않습니다 (models.tts)"}""")
                val dto = ApiJson.decodeFromString(SpeechRequestDto.serializer(), call.receiveText())
                if (dto.text.length > 50_000) return@post call.respondJson(HttpStatusCode.PayloadTooLarge, """{"error":"글이 너무 깁니다"}""")
                try {
                    call.respondBytes(tts.synthesize(dto.text, AudioFormat.M4A), ContentType.parse(AudioFormat.M4A.mime))
                } catch (e: MediaException) {
                    call.respondJson(HttpStatusCode.UnprocessableEntity, buildJsonObject { put("error", e.message.orEmpty()) }.toString())
                }
            }
            // 메시지에 붙인 이미지 (본인 것만)
            get("/media/{id}") {
                val user = call.principal(deps) ?: return@get
                val (asset, bytes) = deps.media?.load(user.userId, call.parameters["id"].orEmpty())
                    ?: return@get call.respondJson(HttpStatusCode.NotFound, """{"error":"없음"}""")
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                // 저장할 때 확인한 이미지 형식만 그대로 보낸다 (HTML 등으로 해석되지 않게)
                val type = asset.mime.takeIf { it in IMAGE_TYPES } ?: "application/octet-stream"
                call.respondBytes(bytes, ContentType.parse(type))
            }
            get("/collections") {
                val user = call.principal(deps) ?: return@get
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(CollectionDto.serializer()), deps.collections(user)))
            }
            get("/dashboard") {
                call.principal(deps) ?: return@get
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(DashboardDto.serializer(), deps.dashboard()))
            }
        }

        route("/v1") {
            get("/models") {
                if (!call.bearer(deps.token)) return@get
                call.respondJson(HttpStatusCode.OK, buildJsonObject {
                    put("object", "list")
                    putJsonArray("data") { add(buildJsonObject { put("id", MODEL_ID); put("object", "model"); put("owned_by", "vesta") }) }
                }.toString())
            }
            post("/chat/completions") {
                if (!call.bearer(deps.token)) return@post
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

        if (deps.serveWebUi) staticResources("/", "web") {
            default("index.html")
            // 파일 이름에 해시가 없으므로 매번 서버에 확인한다 (바뀌지 않았으면 304)
            cacheControl { listOf(io.ktor.http.CacheControl.NoCache(null)) }
        }
    }
}

private const val MODEL_ID = "vesta"
private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

internal sealed interface ImagesResult {
    data class Ok(val images: List<ImageInput>) : ImagesResult
    data class Invalid(val message: String) : ImagesResult
}

/** base64 이미지 확인: 개수·크기·형식(파일 앞부분) */
internal fun decodeImages(encoded: List<String>, limits: MediaConfig): ImagesResult {
    if (encoded.size > limits.maxImages) return ImagesResult.Invalid("이미지는 ${limits.maxImages}장까지 붙일 수 있습니다")
    val images = encoded.map { data ->
        val bytes = runCatching { java.util.Base64.getDecoder().decode(data.substringAfter("base64,")) }.getOrNull()
            ?: return ImagesResult.Invalid("이미지를 읽지 못했습니다 (base64)")
        if (bytes.size > limits.maxImageMegabytes * 1024L * 1024) return ImagesResult.Invalid("이미지가 너무 큽니다 (최대 ${limits.maxImageMegabytes}MB)")
        ImageInput.of(bytes) ?: return ImagesResult.Invalid("PNG·JPEG·WebP·GIF 이미지만 붙일 수 있습니다")
    }
    return ImagesResult.Ok(images)
}

/** 본문을 [megabytes]까지만 받는다. 넘으면 413을 보내고 null */
private suspend fun ApplicationCall.receiveLimited(megabytes: Int): ByteArray? {
    val max = megabytes * 1024L * 1024
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > max) {
        respondJson(HttpStatusCode.PayloadTooLarge, """{"error":"파일이 너무 큽니다 (최대 ${megabytes}MB)"}""")
        return null
    }
    val channel = receiveChannel()
    val bytes = channel.readRemaining(max + 1).readByteArray()
    if (bytes.size > max) {
        respondJson(HttpStatusCode.PayloadTooLarge, """{"error":"파일이 너무 큽니다 (최대 ${megabytes}MB)"}""")
        return null
    }
    if (bytes.isEmpty()) {
        respondJson(HttpStatusCode.BadRequest, """{"error":"빈 파일"}""")
        return null
    }
    return bytes
}
internal const val SESSION_COOKIE = "vesta_session"

/** localhost·루프백과 Tailscale MagicDNS 이름, 설정에 적은 이름만 받는다 */
internal fun hostAllowed(host: String, allowed: Set<String>): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]")
    return name in setOf("localhost", "127.0.0.1", "::1") || name.endsWith(".ts.net") || name in allowed
}

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

private fun dev.vesta.storage.WebSession.toMe() = MeDto(name, user.role.name, csrf)

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
    respondText(body, ContentType.Application.Json, status)

/** `text/event-stream`으로 `data: …` 줄을 흘려보낸다 (POST 요청이라 SSE 플러그인 대신 직접 쓴다) */
private suspend fun ApplicationCall.sse(block: suspend (emit: suspend (String) -> Unit) -> Unit) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    // 프록시(Tailscale serve 등)가 모아서 보내지 않게
    response.header("X-Accel-Buffering", "no")
    respondTextWriter(ContentType.Text.EventStream) {
        block { data ->
            write("data: $data\n\n")
            flush()
        }
    }
}

/** Tailscale serve는 HTTPS를 받아 이 서버(HTTP)로 넘긴다 */
private fun ApplicationCall.isHttps(): Boolean =
    request.origin.scheme == "https" || request.header("X-Forwarded-Proto") == "https" || request.host().endsWith(".ts.net")

/**
 * 요청한 사용자. Bearer 토큰(CLI)이면 OWNER, 세션 쿠키(웹 UI)면 그 사용자.
 * 쿠키 인증의 GET 외 요청은 CSRF 헤더가 세션 값과 같아야 한다. 실패하면 응답을 보내고 null.
 */
private suspend fun ApplicationCall.principal(deps: ServerDeps): UserContext? {
    if (request.header(HttpHeaders.Authorization) != null) return if (bearer(deps.token)) deps.owner else null
    val session = request.cookies[SESSION_COOKIE]?.let { deps.auth?.session(it) }
    if (session == null) {
        respondJson(HttpStatusCode.Unauthorized, """{"error":"로그인이 필요합니다"}""")
        return null
    }
    if (request.httpMethod != HttpMethod.Get && request.httpMethod != HttpMethod.Head) {
        val given = request.header(CSRF_HEADER).orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), session.csrf.toByteArray())) {
            respondJson(HttpStatusCode.Forbidden, """{"error":"CSRF 확인 실패"}""")
            return null
        }
    }
    return session.user
}

/** Bearer 토큰 확인 (시간 차 공격을 피하려고 고정 시간 비교) */
private suspend fun ApplicationCall.bearer(token: String): Boolean {
    val given = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim().orEmpty()
    val ok = MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    if (!ok) respondJson(HttpStatusCode.Unauthorized, """{"error":"토큰이 필요합니다 (Authorization: Bearer …)"}""")
    return ok
}
