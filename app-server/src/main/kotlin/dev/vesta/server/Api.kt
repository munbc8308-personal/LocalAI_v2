package dev.vesta.server

import dev.vesta.api.ApiJson
import dev.vesta.api.ApprovalDto
import dev.vesta.api.ActivateDto
import dev.vesta.api.AttachmentDto
import dev.vesta.api.TrainingRunDto
import dev.vesta.api.TrainingStateDto
import dev.vesta.api.FeedbackDto
import dev.vesta.api.MediaItemDto
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
import dev.vesta.api.UserDto
import dev.vesta.api.NewUserDto
import dev.vesta.api.RoleDto
import dev.vesta.api.PasswordDto
import dev.vesta.api.ConsentDto
import dev.vesta.api.ChangePasswordDto
import dev.vesta.api.toDto
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.config.MediaConfig
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.ImageInput
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.MediaKind
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
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
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
    /** 웹에서 문서 컬렉션 만들기·올리기 (M36 F-04). 없으면 그 경로는 503 */
    val documents: dev.vesta.app.DocumentUploads? = null,
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
    /** 답변 피드백 (C-09) */
    val feedback: dev.vesta.core.training.FeedbackRepository? = null,
    /** 피드백 저장 + 학습 재료 (L-20). 없으면 [feedback]에만 저장 */
    val recordFeedback: (suspend (UserContext, dev.vesta.core.training.Feedback) -> Boolean)? = null,
    /** 모델 학습 (M14). OWNER만 */
    val training: TrainingEndpoints? = null,
    /** 사용자 관리 (M15, U-01). 목록·추가·역할·삭제는 OWNER만, 본인 비밀번호 바꾸기는 누구나 */
    val users: dev.vesta.identity.UserService? = null,
    /** 주간 자가 개선 (M16). OWNER만 */
    val evolution: EvolutionDesk? = null,
    /** 백그라운드 작업 (M24 영상) */
    val jobs: dev.vesta.core.media.BackgroundJobs? = null,
    /** 메신저 계정 연결 (M20): 본인 것은 누구나, 다른 사용자 것은 OWNER만 */
    val messengers: MessengerEndpoints? = null,
    /** 에이전트 스튜디오 (M22) */
    val studio: StudioEndpoints? = null,
    /** 사용자별 설정 (U-07, M20)과 고를 수 있는 목소리 */
    val userSettings: dev.vesta.core.identity.UserSettingsRepository? = null,
    val voices: () -> List<String> = { emptyList() },
    /** 장기 기억 (M27). GUEST는 쓰지 않는다 */
    val memories: dev.vesta.memory.MemoryService? = null,
    /** 자동 기억 지금 돌리기 (Mac 주인): 새로 기억한 문장들 */
    val memoryScan: (suspend () -> List<String>)? = null,
    /** 지난 대화 검색 (M30) */
    val pastTalk: dev.vesta.memory.PastTalk? = null,
    /** 예약 작업 (M29) */
    val schedules: dev.vesta.scheduler.Scheduler? = null,
    val scheduleRepo: dev.vesta.core.schedule.ScheduleRepository? = null,
    /** 지인 공개 (M26): 터널 포트로 온 요청 거르기. null이면 터널 포트를 열지 않는다 */
    val publicGate: PublicGate? = null,
    /** 사용자의 바깥 접속 이메일 (M26) */
    val userEmail: suspend (dev.vesta.core.UserId) -> String? = { null },
)

/** 메신저 연결 (M20, U-02). 웹 UI에서 일회용 코드를 만들어 봇에게 보내면 그 사용자로 연결된다 (터미널의 vesta <메신저> pair와 같다) */
class MessengerEndpoints(
    val bindings: dev.vesta.core.channel.ChannelBindingStore,
    /** 지금 켜진 메신저 (토큰이 있어 봇이 돈다) */
    val enabled: () -> Set<ChannelType>,
    val userName: suspend (dev.vesta.core.UserId) -> String?,
    val findUser: suspend (String) -> dev.vesta.core.identity.User?,
) {
    suspend fun links(filter: (dev.vesta.core.channel.ChannelBinding) -> Boolean): List<dev.vesta.api.MessengerLinkDto> =
        MESSENGERS.flatMap { bindings.list(it) }.filter(filter).map { dev.vesta.api.MessengerLinkDto(it.channel.name, it.externalUserId, userName(it.user.userId) ?: "?") }

    suspend fun pair(user: dev.vesta.core.UserId, channel: ChannelType): dev.vesta.api.PairCodeDto {
        val code = dev.vesta.channels.PairingCode.generate()
        bindings.createPairing(user, channel, dev.vesta.channels.PairingCode.hash(code), PAIR_TTL)
        val how = when (channel) {
            ChannelType.TELEGRAM -> "Telegram에서 Vesta 봇을 열고 이 코드를 보내세요"
            ChannelType.DISCORD -> "Discord에서 Vesta 봇에게 DM(개인 메시지)으로 이 코드를 보내세요"
            else -> "Slack에서 Vesta 앱의 메시지 탭에 이 코드를 보내세요"
        }
        return dev.vesta.api.PairCodeDto(channel.name, code.chunked(4).joinToString("-"), PAIR_TTL.seconds, how)
    }

    companion object {
        val MESSENGERS = listOf(ChannelType.TELEGRAM, ChannelType.DISCORD, ChannelType.SLACK)
        val PAIR_TTL: java.time.Duration = java.time.Duration.ofMinutes(10)
    }
}

/** 학습 API가 쓰는 동작 (서버가 TrainingService로 채운다) */
class TrainingEndpoints(
    val state: suspend () -> TrainingStateDto,
    val start: suspend () -> TrainingRunDto,
    val cancel: suspend (Long) -> Boolean,
    val activate: suspend (String, String) -> Boolean,
    /** 임베딩 학습 시작 (L-23) */
    val startEmbedding: suspend () -> TrainingRunDto = { error("임베딩 학습을 쓸 수 없습니다") },
    /** 메인 모델 학습 시작 (L-24): 후보만 만든다 */
    val startGeneral: suspend () -> TrainingRunDto = { error("메인 모델 학습을 쓸 수 없습니다") },
    /** 학습 재료 목록 (상태로 거르기)·검토 (L-26) */
    val samples: suspend (String?) -> List<dev.vesta.api.TrainingSampleDto> = { emptyList() },
    val review: suspend (Long, dev.vesta.api.SampleReviewDto) -> Boolean = { _, _ -> false },
    /** 지금 모으기: 찾은 재료 수 */
    val curate: suspend () -> Int = { 0 },
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
        val gate = deps.publicGate
        if (gate != null && call.request.local.localPort == gate.port) {
            // 바깥 입구로 온 요청 (M26): 공개 주소·Access 토큰 확인, 토큰 API 막기, IP별 제한
            val ip = call.clientIp(gate.direct)
            when (val result = gate.check(call.request.host(), call.request.path(), call.request.header(HttpHeaders.Authorization), call.request.header(ACCESS_HEADER), ip)) {
                is PublicGate.Result.Pass -> call.attributes.put(PUBLIC_REQUEST, PublicRequest(result.email, ip))
                is PublicGate.Result.Reject -> {
                    call.respondJson(HttpStatusCode.fromValue(result.status), buildJsonObject { put("error", result.message) }.toString())
                    finish()
                    return@intercept
                }
            }
        } else if (!hostAllowed(call.request.host(), deps.allowedHosts)) {
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
            // 상태(버전·구성 요소)는 바깥에 알리지 않는다
            if (call.attributes.contains(PUBLIC_REQUEST)) return@get call.respondJson(HttpStatusCode.NotFound, """{"error":"없음"}""")
            call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(HealthDto.serializer(), HealthDto("ok", deps.version, deps.health())))
        }

        route("/api") {
            post("/login") {
                val auth = deps.auth ?: return@post call.respondJson(HttpStatusCode.NotFound, """{"error":"웹 로그인이 꺼져 있음"}""")
                // 바깥 로그인 시도는 IP별로 센다 (라우트 안에서: 경로를 어떻게 적어도 여기로 오면 센다)
                call.attributes.getOrNull(PUBLIC_REQUEST)?.let { public ->
                    if (deps.publicGate?.allowLogin(public.ip) == false) return@post call.respondJson(HttpStatusCode.TooManyRequests, """{"error":"로그인 시도가 너무 많습니다. 10분 뒤에 다시 시도하세요"}""")
                }
                val dto = ApiJson.decodeFromString(LoginDto.serializer(), call.receiveText())
                // 바깥에서는 비밀번호를 보기 전에 계정 이메일이 Access 이메일과 같은지 본다: 다르면 틀린 비밀번호와 같은 답.
                // 비밀번호가 맞는지 알려 주지 않고, 남의 계정 잠금 횟수도 올리지 않는다 (보안 검토)
                if (!call.publicLoginAllowed(deps, dto.name)) return@post call.respondJson(HttpStatusCode.Unauthorized, """{"error":"비밀번호가 맞지 않습니다"}""")
                // 직접 모드(앞단 로그인 없음)는 긴 비밀번호만 받는다. 입력 길이만 보므로 계정에 대해 알려 주는 것은 없다
                val minimum = deps.publicGate?.minPassword?.takeIf { call.attributes.contains(PUBLIC_REQUEST) }
                if (minimum != null && dto.password.length < minimum) {
                    return@post call.respondJson(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "바깥에서는 ${minimum}자 이상 비밀번호로만 로그인할 수 있습니다 (이 Mac에서 vesta user passwd 로 바꾸세요)") }.toString())
                }
                when (val result = auth.login(dto.name, dto.password, call.request.header(HttpHeaders.UserAgent))) {
                    is LoginResult.Ok -> {
                        // 위에서 확인했지만 한 번 더 (M26)
                        call.publicMismatch(deps, result.session.user)?.let { message ->
                            auth.logout(result.token)
                            return@post call.respondJson(HttpStatusCode.Forbidden, buildJsonObject { put("error", message) }.toString())
                        }
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
                call.publicMismatch(deps, session.user)?.let { return@get call.respondJson(HttpStatusCode.Unauthorized, buildJsonObject { put("error", it) }.toString()) }
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
                    // 작업 폴더는 CLI에서만 받는다 (대화 서비스가 Mac 주인·홈 아래 폴더인지 다시 확인한다)
                    val workspace = request.workspace?.takeIf { channel == ChannelType.CLI }?.let { path ->
                        val skills = request.skills.take(100).mapNotNull { dev.vesta.core.skill.Skill.parse(it.folder, it.text, dev.vesta.core.skill.SkillSource.PROJECT) }
                        dev.vesta.core.agent.Workspace(path, request.instructions, skills)
                    }
                    // 대화 서비스가 예외로 끝나면(DB 잠김 등) 조용히 끊지 않고 오류를 알린다 (팀 검증, M36)
                    try {
                        deps.conversation.send(user, channel, request.sessionId?.let(::SessionId), UserInput(request.text, images, workspace, spoken = request.spoken))
                            .collect { emit(ApiJson.encodeToString(EventDto.serializer(), it.toDto())) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(e) { "대화 실패" }
                        runCatching { emit(ApiJson.encodeToString(EventDto.serializer(), dev.vesta.core.agent.AgentEvent.Error("답을 만들지 못했습니다. 잠시 뒤 다시 보내 주세요.").toDto())) }
                    }
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
                val list = deps.sessions.listRecent(user.userId, limit).map { SessionDto(it.id.value, it.title, it.originChannel.name, it.updatedAt.toString(), it.systemPrompt) }
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(SessionDto.serializer()), list))
            }
            // 지난 대화 찾기 (M30 H-05): 본인 대화만, 손님은 안 됨
            get("/sessions/search") {
                val user = call.principal(deps) ?: return@get
                val past = deps.pastTalk ?: return@get call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"검색을 쓸 수 없습니다"}""")
                if (user.role == UserRole.GUEST) return@get call.respondJson(HttpStatusCode.Forbidden, """{"error":"손님 계정은 지난 대화를 찾을 수 없습니다"}""")
                val q = call.request.queryParameters["q"].orEmpty().trim().take(200)
                val hits = if (q.isEmpty()) emptyList() else past.find(user, q, limit = 30).map { dev.vesta.api.SessionHitDto(it.session.value, it.title, it.at.toString(), it.snippet) }
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(dev.vesta.api.SessionHitDto.serializer()), hits))
            }
            // 대화 지우기: 메시지·라우팅 기록과 이 대화에 붙은 이미지·영상까지 (본인 것만)
            delete("/sessions/{id}") {
                val user = call.principal(deps) ?: return@delete
                val id = SessionId(call.parameters["id"].orEmpty())
                if (deps.sessions.find(user.userId, id) == null) return@delete call.respondJson(HttpStatusCode.NotFound, """{"error":"세션 없음"}""")
                val media = deps.media?.deleteSession(user.userId, id.value) ?: 0
                val removed = deps.sessions.delete(user.userId, id)
                call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed,"media":$media}""")
            }
            // 이 대화 지침 (C-06, M34 Q-03): 본인 대화만
            put("/sessions/{id}/prompt") {
                val user = call.principal(deps) ?: return@put
                val dto = ApiJson.decodeFromString(dev.vesta.api.SessionPromptDto.serializer(), call.receiveText())
                val text = dto.prompt?.trim()?.ifEmpty { null }
                text?.let { dev.vesta.core.session.SessionPrompt.problem(it) }?.let { return@put call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", it) }.toString()) }
                val ok = deps.sessions.updateSystemPrompt(user.userId, SessionId(call.parameters["id"].orEmpty()), text)
                call.respondJson(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, if (ok) """{"ok":true}""" else """{"error":"세션 없음"}""")
            }
            get("/sessions/{id}/messages") {
                val user = call.principal(deps) ?: return@get
                val id = SessionId(call.parameters["id"].orEmpty())
                if (deps.sessions.find(user.userId, id) == null) {
                    call.respondJson(HttpStatusCode.NotFound, """{"error":"세션 없음"}""")
                    return@get
                }
                val list = deps.messages.list(id).map {
                    val feedback = if (it.role == dev.vesta.core.inference.ChatRole.ASSISTANT) deps.feedback?.find(user.userId, it.id) else null
                    MessageDto(
                        it.id, it.role.name, it.content, it.agent?.name, it.model, it.interrupted, it.createdAt.toString(),
                        it.attachments.map { a -> AttachmentDto(a.id, a.mime) },
                        feedback?.rating?.takeIf { r -> r != 0 }, feedback?.correctAgent?.name,
                    )
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
                val user = call.principal(deps) ?: return@post
                val tts = deps.textToSpeech ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"읽어 주기가 설정돼 있지 않습니다 (models.tts)"}""")
                val dto = ApiJson.decodeFromString(SpeechRequestDto.serializer(), call.receiveText())
                if (dto.text.length > 50_000) return@post call.respondJson(HttpStatusCode.PayloadTooLarge, """{"error":"글이 너무 깁니다"}""")
                try {
                    // 내 설정의 목소리로 (U-07)
                    val voice = deps.userSettings?.get(user.userId)?.voice?.takeIf { it in deps.voices() }
                    call.respondBytes(tts.synthesize(dto.text, AudioFormat.M4A, voice), ContentType.parse(AudioFormat.M4A.mime))
                } catch (e: MediaException) {
                    call.respondJson(HttpStatusCode.UnprocessableEntity, buildJsonObject { put("error", e.message.orEmpty()) }.toString())
                }
            }
            // 백그라운드 작업 진행 (M24): 본인 것만
            get("/jobs/{id}") {
                val user = call.principal(deps) ?: return@get
                val job = deps.jobs?.get(user.userId, call.parameters["id"].orEmpty())
                    ?: return@get call.respondJson(HttpStatusCode.NotFound, """{"error":"없음"}""")
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.JobDto.serializer(),
                    dev.vesta.api.JobDto(job.id, job.title, job.status.name, job.progress, job.mediaId, job.mime, job.text)))
            }
            // 갤러리: 만든 이미지 목록 (V-08)
            get("/media") {
                val user = call.principal(deps) ?: return@get
                val kind = runCatching { MediaKind.valueOf(call.request.queryParameters["kind"] ?: "GENERATED") }.getOrNull()
                    ?: return@get call.respondJson(HttpStatusCode.BadRequest, """{"error":"kind"}""")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val list = deps.media?.list(user.userId, kind, limit).orEmpty().map { MediaItemDto(it.id, it.mime, it.caption, it.createdAt?.toString()) }
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(MediaItemDto.serializer()), list))
            }
            // 갤러리에서 지우기 (본인 것만). 대화의 답에서도 사라진다
            delete("/media/{id}") {
                val user = call.principal(deps) ?: return@delete
                val removed = deps.media?.delete(user.userId, call.parameters["id"].orEmpty()) ?: false
                call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
            }
            // 메시지에 붙인 이미지 (본인 것만)
            get("/media/{id}") {
                val user = call.principal(deps) ?: return@get
                val (asset, bytes) = deps.media?.load(user.userId, call.parameters["id"].orEmpty())
                    ?: return@get call.respondJson(HttpStatusCode.NotFound, """{"error":"없음"}""")
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                // 저장할 때 확인한 이미지·Vesta가 만든 영상 형식만 그대로 보낸다 (HTML 등으로 해석되지 않게)
                val type = asset.mime.takeIf { it in IMAGE_TYPES || it == VIDEO_MP4 } ?: "application/octet-stream"
                if (type == VIDEO_MP4) {
                    // 영상은 일부 범위 요청(Range)에 답한다: Safari·iOS는 이것이 없으면 재생하지 않는다
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    val range = MediaRange.parse(call.request.headers[HttpHeaders.Range], bytes.size)
                    if (range != null) {
                        call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${bytes.size}")
                        return@get call.respondBytes(bytes.copyOfRange(range.first, range.last + 1), ContentType.parse(type), HttpStatusCode.PartialContent)
                    }
                }
                call.respondBytes(bytes, ContentType.parse(type))
            }
            // 답변 피드백·라우팅 정정 (C-09). 정정은 결정 모델 학습 후보가 된다 (L-01)
            post("/messages/{id}/feedback") {
                val user = call.principal(deps) ?: return@post
                val repo = deps.feedback ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"피드백 저장소 없음"}""")
                val messageId = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondJson(HttpStatusCode.BadRequest, """{"error":"id"}""")
                val dto = ApiJson.decodeFromString(FeedbackDto.serializer(), call.receiveText())
                val agent = dto.correctAgent?.let { name -> runCatching { dev.vesta.core.AgentType.valueOf(name) }.getOrNull() ?: return@post call.respondJson(HttpStatusCode.BadRequest, """{"error":"correctAgent"}""") }
                val value = dev.vesta.core.training.Feedback(messageId, dto.rating, agent, dto.comment)
                val ok = deps.recordFeedback?.invoke(user, value) ?: repo.save(user.userId, value)
                call.respondJson(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, buildJsonObject { put("saved", ok) }.toString())
            }
            // 주간 자가 개선 (E-05·E-07): 점검 결과, 제안 승인·거절·되돌리기. Mac 주인만
            route("/evolution") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val desk = call.evolutionFor(user, deps) ?: return@get
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.EvolutionStateDto.serializer(), desk.state()))
                }
                post("/{id}/{action}") {
                    val user = call.principal(deps) ?: return@post
                    val desk = call.evolutionFor(user, deps) ?: return@post
                    val result = desk.decide(user, call.parameters["id"]?.toLongOrNull() ?: -1, call.parameters["action"].orEmpty())
                    if (!result.ok) return@post call.respondJson(HttpStatusCode.Conflict, buildJsonObject { put("error", result.message) }.toString())
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.ProposalResultDto.serializer(), result))
                }
            }
            // 모델 학습 (L-05·L-07): 상태, 시작, 취소, 버전 전환·되돌리기. Mac 주인만
            route("/training") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val training = call.trainingFor(user, deps) ?: return@get
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(TrainingStateDto.serializer(), training.state()))
                }
                post("/decision") {
                    val user = call.principal(deps) ?: return@post
                    val training = call.trainingFor(user, deps) ?: return@post
                    val run = runCatching { training.start() }.getOrElse {
                        return@post call.respondJson(HttpStatusCode.Conflict, buildJsonObject { put("error", it.message ?: "학습을 시작하지 못했습니다") }.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(TrainingRunDto.serializer(), run))
                }
                post("/general") {
                    val user = call.principal(deps) ?: return@post
                    val training = call.trainingFor(user, deps) ?: return@post
                    val run = runCatching { training.startGeneral() }.getOrElse {
                        return@post call.respondJson(HttpStatusCode.Conflict, buildJsonObject { put("error", it.message ?: "학습을 시작하지 못했습니다") }.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(TrainingRunDto.serializer(), run))
                }
                post("/embedding") {
                    val user = call.principal(deps) ?: return@post
                    val training = call.trainingFor(user, deps) ?: return@post
                    val run = runCatching { training.startEmbedding() }.getOrElse {
                        return@post call.respondJson(HttpStatusCode.Conflict, buildJsonObject { put("error", it.message ?: "학습을 시작하지 못했습니다") }.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(TrainingRunDto.serializer(), run))
                }
                post("/{id}/cancel") {
                    val user = call.principal(deps) ?: return@post
                    val training = call.trainingFor(user, deps) ?: return@post
                    val ok = training.cancel(call.parameters["id"]?.toLongOrNull() ?: -1)
                    call.respondJson(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, buildJsonObject { put("cancelled", ok) }.toString())
                }
                // 학습 재료 검토 (L-26): 주인만
                get("/samples") {
                    val user = call.principal(deps) ?: return@get
                    val training = call.trainingFor(user, deps) ?: return@get
                    val status = call.request.queryParameters["status"]?.takeIf { it in setOf("PENDING", "ACCEPTED", "REJECTED") }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(dev.vesta.api.TrainingSampleDto.serializer()), training.samples(status)))
                }
                post("/curate") {
                    val user = call.principal(deps) ?: return@post
                    val training = call.trainingFor(user, deps) ?: return@post
                    call.respondJson(HttpStatusCode.OK, buildJsonObject { put("added", training.curate()) }.toString())
                }
                post("/samples/{id}") {
                    val user = call.principal(deps) ?: return@post
                    val training = call.trainingFor(user, deps) ?: return@post
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.SampleReviewDto.serializer(), call.receiveText()) }.getOrNull()
                        ?.takeIf { it.status == "ACCEPTED" || it.status == "REJECTED" }
                        ?: return@post call.respondJson(HttpStatusCode.BadRequest, """{"error":"status는 ACCEPTED 또는 REJECTED"}""")
                    val ok = training.review(call.parameters["id"]?.toLongOrNull() ?: -1, dto)
                    call.respondJson(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, buildJsonObject { put("reviewed", ok) }.toString())
                }
                post("/activate") {
                    val user = call.principal(deps) ?: return@post
                    val training = call.trainingFor(user, deps) ?: return@post
                    val dto = ApiJson.decodeFromString(ActivateDto.serializer(), call.receiveText())
                    val ok = training.activate(dto.role, dto.name)
                    call.respondJson(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, buildJsonObject { put("activated", ok) }.toString())
                }
            }
            // 사용자 관리 (U-01·U-05): OWNER만
            route("/users") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val users = call.usersFor(user, deps) ?: return@get
                    val usage = users.usageToday()
                    val list = users.list().map {
                        val today = usage[it.id]
                        UserDto(it.name, it.role.name, it.hasPassword, it.trainingConsent, today?.questions ?: 0, today?.completionTokens ?: 0, it.createdAt.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(UserDto.serializer()), list))
                }
                post {
                    val user = call.principal(deps) ?: return@post
                    val users = call.usersFor(user, deps) ?: return@post
                    val dto = ApiJson.decodeFromString(NewUserDto.serializer(), call.receiveText())
                    call.userAction {
                        val role = roleOf(dto.role)
                        val created = users.create(dto.name, role, dto.password?.takeIf { it.isNotEmpty() })
                        UserDto(created.name, created.role.name, created.hasPassword, created.trainingConsent, 0, 0, created.createdAt.toString())
                    }
                }
                post("/{name}/role") {
                    val user = call.principal(deps) ?: return@post
                    val users = call.usersFor(user, deps) ?: return@post
                    val dto = ApiJson.decodeFromString(RoleDto.serializer(), call.receiveText())
                    call.userAction { users.setRole(call.parameters["name"].orEmpty(), roleOf(dto.role)); null }
                }
                post("/{name}/password") {
                    val user = call.principal(deps) ?: return@post
                    val users = call.usersFor(user, deps) ?: return@post
                    val dto = ApiJson.decodeFromString(PasswordDto.serializer(), call.receiveText())
                    call.userAction { users.setPassword(call.parameters["name"].orEmpty(), dto.password); null }
                }
                post("/{name}/consent") {
                    val user = call.principal(deps) ?: return@post
                    val users = call.usersFor(user, deps) ?: return@post
                    val dto = ApiJson.decodeFromString(ConsentDto.serializer(), call.receiveText())
                    call.userAction { users.setConsent(call.parameters["name"].orEmpty(), dto.consent); null }
                }
                delete("/{name}") {
                    val user = call.principal(deps) ?: return@delete
                    val users = call.usersFor(user, deps) ?: return@delete
                    call.userAction { users.delete(call.parameters["name"].orEmpty()); null }
                }
            }
            // 본인 비밀번호 바꾸기 (U-02). 지금 비밀번호 확인은 로그인과 같은 잠금을 쓴다. 바꾸면 모든 기기 로그아웃
            // 에이전트 스튜디오 (M22): 에이전트 목록·만들기·고치기·지우기, 실행 목록·상세·실시간. 본인 것만 (대화처럼)
            route("/agents") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val studio = deps.studio ?: return@get call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.AgentsDto.serializer(), studio.agents(user)))
                }
                put("/{name}") {
                    val user = call.principal(deps) ?: return@put
                    val studio = deps.studio ?: return@put call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.CustomAgentDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    studio.save(user, dto.copy(name = call.parameters["name"].orEmpty()))?.let { error ->
                        return@put call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", error) }.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, """{"saved":true}""")
                }
                delete("/{name}") {
                    val user = call.principal(deps) ?: return@delete
                    val studio = deps.studio ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val removed = studio.agentRepo.delete(user.userId, call.parameters["name"].orEmpty())
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
            }
            // 스킬 (SK-01): 웹에서 만든 것만 고치고 지운다 (파일 스킬은 파일로)
            route("/skills") {
                put("/{name}") {
                    val user = call.principal(deps) ?: return@put
                    val studio = deps.studio ?: return@put call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.SkillDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    studio.saveSkill(user, dto.copy(name = call.parameters["name"].orEmpty()))?.let { error ->
                        return@put call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", error) }.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, """{"saved":true}""")
                }
                delete("/{name}") {
                    val user = call.principal(deps) ?: return@delete
                    val repo = deps.studio?.skillRepo ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val removed = repo.delete(user.userId, call.parameters["name"].orEmpty())
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
            }
            // A2A 에이전트 (A-15): Mac 주인만. 미리 보기(카드 읽기·경고) → 등록, 지우기
            route("/a2a") {
                post("/preview") {
                    val user = call.principal(deps) ?: return@post
                    val studio = deps.studio ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    if (!call.manages(user, deps, "Mac 주인만 A2A 에이전트를 붙일 수 있습니다")) return@post
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.A2aAddDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@post call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    studio.previewA2a(dto.url, dto.local).fold(
                        { call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.A2aPreviewDto.serializer(), it)) },
                        { call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", it.message ?: "카드를 읽지 못했습니다") }.toString()) },
                    )
                }
                put("/{name}") {
                    val user = call.principal(deps) ?: return@put
                    val studio = deps.studio ?: return@put call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    if (!call.manages(user, deps, "Mac 주인만 A2A 에이전트를 붙일 수 있습니다")) return@put
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.A2aAddDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    studio.saveA2a(user, call.parameters["name"].orEmpty(), dto.url, dto.endpoint, dto.local)?.let { error ->
                        return@put call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", error) }.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, """{"saved":true}""")
                }
                delete("/{name}") {
                    val user = call.principal(deps) ?: return@delete
                    val repo = deps.studio?.a2aRepo ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    if (!call.manages(user, deps, "Mac 주인만")) return@delete
                    val removed = repo.delete(user.userId, call.parameters["name"].orEmpty())
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
            }
            // 팀 (A-14): 리더는 내 사용자 정의 에이전트, 멤버는 사용자 정의·A2A 에이전트
            route("/teams") {
                put("/{name}") {
                    val user = call.principal(deps) ?: return@put
                    val studio = deps.studio ?: return@put call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.TeamDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    studio.saveTeam(user, dto.copy(name = call.parameters["name"].orEmpty()))?.let { error ->
                        return@put call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", error) }.toString())
                    }
                    call.respondJson(HttpStatusCode.OK, """{"saved":true}""")
                }
                delete("/{name}") {
                    val user = call.principal(deps) ?: return@delete
                    val teams = deps.studio?.teamRepo ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val removed = teams.delete(user.userId, call.parameters["name"].orEmpty())
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
            }
            route("/runs") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val studio = deps.studio ?: return@get call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                    val runs = studio.runRepo.list(user.userId, limit).map { it.dto() }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(dev.vesta.api.RunDto.serializer()), runs))
                }
                get("/stream") {
                    val user = call.principal(deps) ?: return@get
                    val studio = deps.studio ?: return@get call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    call.sse { emit ->
                        kotlinx.coroutines.coroutineScope {
                            // 프록시가 끊지 않게 20초마다 빈 소식
                            val ping = launch { while (true) { kotlinx.coroutines.delay(20_000); emit("{}") } }
                            try {
                                studio.updates.collect { update ->
                                    if (update.user != user.userId) return@collect
                                    val dto = dev.vesta.api.RunUpdateDto(update.finished?.dto(), update.event?.dto())
                                    emit(ApiJson.encodeToString(dev.vesta.api.RunUpdateDto.serializer(), dto))
                                }
                            } finally {
                                ping.cancel()
                            }
                        }
                    }
                }
                // 실행 기록 지우기 (본인 것, 끝난 것만). 대화는 남는다
                delete {
                    val user = call.principal(deps) ?: return@delete
                    val studio = deps.studio ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    call.respondJson(HttpStatusCode.OK, """{"removed":${studio.runRepo.deleteFinished(user.userId)}}""")
                }
                delete("/{id}") {
                    val user = call.principal(deps) ?: return@delete
                    val studio = deps.studio ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val removed = call.parameters["id"]?.toLongOrNull()?.let { studio.runRepo.delete(user.userId, it) } ?: false
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
                get("/{id}") {
                    val user = call.principal(deps) ?: return@get
                    val studio = deps.studio ?: return@get call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"스튜디오를 쓸 수 없습니다"}""")
                    val run = call.parameters["id"]?.toLongOrNull()?.let { studio.runRepo.get(it) }?.takeIf { it.user == user.userId }
                        ?: return@get call.respondJson(HttpStatusCode.NotFound, """{"error":"없는 실행입니다"}""")
                    val detail = dev.vesta.api.RunDetailDto(run.dto(), studio.runRepo.events(run.id).map { it.dto() }, run.answer)
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.RunDetailDto.serializer(), detail))
                }
            }
            // 내 설정 (U-07, M20): 호칭·말투·음성 답장·목소리
            route("/me/settings") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val repo = deps.userSettings ?: return@get call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"설정을 쓸 수 없습니다"}""")
                    val s = repo.get(user.userId)
                    val dto = dev.vesta.api.SettingsDto(s.nickname, s.tone.name, s.voiceReply, s.voice, deps.voices(), s.memoryAuto, s.skillAuto)
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.SettingsDto.serializer(), dto))
                }
                put {
                    val user = call.principal(deps) ?: return@put
                    val repo = deps.userSettings ?: return@put call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"설정을 쓸 수 없습니다"}""")
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.SettingsDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    val nickname = dto.nickname?.trim()?.ifEmpty { null }
                    if (nickname != null && !dev.vesta.core.identity.UserSettings.NICKNAME.matches(nickname)) {
                        return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"부르는 이름은 글자·숫자·공백 20자까지입니다"}""")
                    }
                    val tone = runCatching { dev.vesta.core.identity.Tone.valueOf(dto.tone) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"말투는 DEFAULT·FORMAL·CASUAL 중 하나입니다"}""")
                    val voice = dto.voice?.ifBlank { null }
                    if (voice != null && voice !in deps.voices()) return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"이 Mac에 없는 목소리입니다"}""")
                    repo.save(user.userId, dev.vesta.core.identity.UserSettings(nickname, tone, dto.voiceReply, voice, dto.memoryAuto, dto.skillAuto))
                    call.respondJson(HttpStatusCode.OK, """{"saved":true}""")
                }
            }
            // 장기 기억 (M27 MEM-05): 내 기억 보기·더하기·고치기·지우기
            // 예약 작업 (M29 SC-04): 본인 것만, 손님은 안 됨. 웹에서 만든 예약은 결과가 웹 대화 목록에 남는다
            route("/schedules") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val (scheduler, repo) = call.schedulesFor(user, deps) ?: return@get
                    val list = repo.list(user.userId).mapIndexed { i, s ->
                        dev.vesta.api.ScheduleDto(s.id, i + 1, s.task, s.spec, scheduler.describe(s), s.channel.name, s.enabled, s.nextAt?.toString(), s.lastAt?.toString(), s.lastStatus)
                    }
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(dev.vesta.api.ScheduleDto.serializer()), list))
                }
                post {
                    val user = call.principal(deps) ?: return@post
                    val (scheduler, _) = call.schedulesFor(user, deps) ?: return@post
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.ScheduleNewDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@post call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    scheduler.add(user.userId, ChannelType.WEB, dto.text).fold(
                        { call.respondJson(HttpStatusCode.OK, buildJsonObject { put("id", it.id); put("nextAt", it.nextAt?.toString()) }.toString()) },
                        { call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", it.message ?: "예약하지 못했습니다") }.toString()) },
                    )
                }
                put("/{id}") {
                    val user = call.principal(deps) ?: return@put
                    val (scheduler, _) = call.schedulesFor(user, deps) ?: return@put
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respondJson(HttpStatusCode.NotFound, """{"error":"없는 예약입니다"}""")
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.ScheduleEditDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    if (scheduler.setEnabled(user.userId, id, dto.enabled)) call.respondJson(HttpStatusCode.OK, """{"saved":true}""")
                    else call.respondJson(HttpStatusCode.BadRequest, """{"error":"없는 예약이거나 다시 돌 때가 없습니다"}""")
                }
                post("/{id}/run") {
                    val user = call.principal(deps) ?: return@post
                    val (scheduler, _) = call.schedulesFor(user, deps) ?: return@post
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondJson(HttpStatusCode.NotFound, """{"error":"없는 예약입니다"}""")
                    val problem = scheduler.runNow(user, id)
                    if (problem == null) call.respondJson(HttpStatusCode.Accepted, """{"started":true}""")
                    else call.respondJson(HttpStatusCode.Conflict, buildJsonObject { put("error", problem) }.toString())
                }
                delete("/{id}") {
                    val user = call.principal(deps) ?: return@delete
                    val (_, repo) = call.schedulesFor(user, deps) ?: return@delete
                    val removed = call.parameters["id"]?.toLongOrNull()?.let { repo.delete(user.userId, it) } ?: false
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
            }
            route("/memories") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val memories = call.memoriesFor(user, deps) ?: return@get
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(dev.vesta.api.MemoryDto.serializer()), memories.list(user.userId).map { it.dto() }))
                }
                post {
                    val user = call.principal(deps) ?: return@post
                    val memories = call.memoriesFor(user, deps) ?: return@post
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.MemoryEditDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@post call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    call.memorySaved(memories.remember(user.userId, dto.text, dev.vesta.core.memory.MemorySource.EXPLICIT, dto.kind?.let(::memoryKind)))
                }
                put("/{id}") {
                    val user = call.principal(deps) ?: return@put
                    val memories = call.memoriesFor(user, deps) ?: return@put
                    val id = call.parameters["id"]?.toLongOrNull() ?: return@put call.respondJson(HttpStatusCode.NotFound, """{"error":"없는 기억입니다"}""")
                    val dto = runCatching { ApiJson.decodeFromString(dev.vesta.api.MemoryEditDto.serializer(), call.receiveText()) }.getOrNull()
                        ?: return@put call.respondJson(HttpStatusCode.BadRequest, """{"error":"잘못된 요청입니다"}""")
                    call.memorySaved(memories.edit(user.userId, id, dto.text, dto.kind?.let(::memoryKind)))
                }
                delete("/{id}") {
                    val user = call.principal(deps) ?: return@delete
                    val memories = call.memoriesFor(user, deps) ?: return@delete
                    val removed = call.parameters["id"]?.toLongOrNull()?.let { memories.delete(user.userId, it) } ?: false
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
                delete {
                    val user = call.principal(deps) ?: return@delete
                    val memories = call.memoriesFor(user, deps) ?: return@delete
                    call.respondJson(HttpStatusCode.OK, """{"removed":${memories.clear(user.userId)}}""")
                }
                // 자동 기억을 지금 한 번 (쉬는 때를 기다리지 않음). 모든 사용자의 새 질문을 본다 → Mac 주인만
                post("/scan") {
                    val user = call.principal(deps) ?: return@post
                    val scan = deps.memoryScan ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"자동 기억을 쓸 수 없습니다"}""")
                    if (!call.manages(user, deps, "자동 기억 돌리기는 Mac 주인만 할 수 있습니다")) return@post
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), scan()))
                }
            }
            // 메신저 연결 (M20): 내 연결 목록, 내 연결 코드 만들기·끊기
            route("/me/messengers") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val m = deps.messengers ?: return@get call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"메신저가 설정돼 있지 않습니다"}""")
                    val dto = dev.vesta.api.MessengersDto(m.enabled().map { it.name }.sorted(), m.links { it.user.userId == user.userId })
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.MessengersDto.serializer(), dto))
                }
                post("/{channel}/pair") {
                    val user = call.principal(deps) ?: return@post
                    val m = deps.messengers ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"메신저가 설정돼 있지 않습니다"}""")
                    val channel = call.messengerOf(m) ?: return@post
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.PairCodeDto.serializer(), m.pair(user.userId, channel)))
                }
                delete("/{channel}/{externalId}") {
                    val user = call.principal(deps) ?: return@delete
                    val m = deps.messengers ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"메신저가 설정돼 있지 않습니다"}""")
                    val channel = MessengerEndpoints.MESSENGERS.firstOrNull { it.name == call.parameters["channel"] }
                    val id = call.parameters["externalId"].orEmpty()
                    // 내 연결만 끊을 수 있다
                    val mine = channel != null && m.bindings.find(channel, id)?.user?.userId == user.userId
                    if (!mine) return@delete call.respondJson(HttpStatusCode.NotFound, """{"error":"내 연결이 아닙니다"}""")
                    m.bindings.remove(channel!!, id)
                    call.respondJson(HttpStatusCode.OK, """{"removed":true}""")
                }
            }
            // 모든 사용자의 메신저 연결 (OWNER): 목록, 다른 사용자 연결 코드 만들기, 끊기
            route("/messengers") {
                get {
                    val user = call.principal(deps) ?: return@get
                    val m = call.messengersFor(user, deps) ?: return@get
                    val dto = dev.vesta.api.MessengersDto(m.enabled().map { it.name }.sorted(), m.links { true })
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.MessengersDto.serializer(), dto))
                }
                post("/{channel}/pair/{name}") {
                    val user = call.principal(deps) ?: return@post
                    val m = call.messengersFor(user, deps) ?: return@post
                    val channel = call.messengerOf(m) ?: return@post
                    val target = m.findUser(call.parameters["name"].orEmpty())
                        ?: return@post call.respondJson(HttpStatusCode.NotFound, """{"error":"없는 사용자입니다"}""")
                    call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.PairCodeDto.serializer(), m.pair(target.id, channel)))
                }
                delete("/{channel}/{externalId}") {
                    val user = call.principal(deps) ?: return@delete
                    val m = call.messengersFor(user, deps) ?: return@delete
                    val channel = MessengerEndpoints.MESSENGERS.firstOrNull { it.name == call.parameters["channel"] }
                        ?: return@delete call.respondJson(HttpStatusCode.NotFound, """{"error":"없는 메신저입니다"}""")
                    val removed = m.bindings.remove(channel, call.parameters["externalId"].orEmpty())
                    call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, """{"removed":$removed}""")
                }
            }
            post("/me/password") {
                val user = call.principal(deps) ?: return@post
                val users = deps.users ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"사용자 관리가 꺼져 있음"}""")
                val auth = deps.auth ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"웹 로그인이 꺼져 있음"}""")
                val dto = ApiJson.decodeFromString(ChangePasswordDto.serializer(), call.receiveText())
                // 직접 공개 중이면 바깥 로그인 하한(12자)보다 짧게 바꾸지 못한다: 바꾼 뒤 바깥에서 못 들어오게 갇혔다 (실제 점검)
                deps.publicGate?.minPassword?.let { minimum ->
                    if (dto.new.length < minimum) return@post call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", "바깥에서도 로그인하려면 비밀번호는 ${minimum}자 이상이어야 합니다") }.toString())
                }
                when (val failed = auth.verify(user, dto.current)) {
                    null -> Unit
                    is LoginResult.Locked -> {
                        call.response.header(HttpHeaders.RetryAfter, failed.retryAfterSeconds.toString())
                        return@post call.respondJson(HttpStatusCode.TooManyRequests, """{"error":"여러 번 틀려 잠시 잠겼습니다. ${failed.retryAfterSeconds}초 뒤에 다시 시도하세요"}""")
                    }
                    else -> return@post call.respondJson(HttpStatusCode.Forbidden, """{"error":"지금 비밀번호가 맞지 않습니다"}""")
                }
                call.userAction { users.setOwnPassword(user.userId, dto.new); null }
            }
            get("/collections") {
                val user = call.principal(deps) ?: return@get
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(CollectionDto.serializer()), deps.collections(user)))
            }
            // 웹에서 문서 올리기 (M36 F-04): 본인 컬렉션만, 손님은 안 됨. 색인은 백그라운드
            post("/collections") {
                val user = call.principal(deps) ?: return@post
                val docs = deps.documents ?: return@post call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"문서 올리기를 쓸 수 없습니다"}""")
                val dto = ApiJson.decodeFromString(dev.vesta.api.NewCollectionDto.serializer(), call.receiveText())
                docs.create(user, dto.name.trim(), dto.description)?.let { return@post call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", it) }.toString()) }
                call.respondJson(HttpStatusCode.OK, """{"ok":true}""")
            }
            delete("/collections/{name}") {
                val user = call.principal(deps) ?: return@delete
                val docs = deps.documents ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"문서 올리기를 쓸 수 없습니다"}""")
                val removed = docs.deleteCollection(user, call.parameters["name"].orEmpty())
                call.respondJson(if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound, if (removed) """{"ok":true}""" else """{"error":"컬렉션이 없습니다"}""")
            }
            get("/collections/{name}/documents") {
                val user = call.principal(deps) ?: return@get
                val list = deps.documents?.documents(user, call.parameters["name"].orEmpty()) ?: return@get call.respondJson(HttpStatusCode.NotFound, """{"error":"컬렉션이 없습니다"}""")
                call.respondJson(HttpStatusCode.OK, ApiJson.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), list))
            }
            put("/collections/{name}/documents/{file}") {
                val user = call.principal(deps) ?: return@put
                val docs = deps.documents ?: return@put call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"문서 올리기를 쓸 수 없습니다"}""")
                if (!dev.vesta.identity.RolePolicy.usesLocalData(user.role)) return@put call.respondJson(HttpStatusCode.Forbidden, """{"error":"손님 계정은 문서를 올릴 수 없습니다"}""")
                val bytes = call.receiveLimited(dev.vesta.app.DocumentUploads.MAX_MEGABYTES) ?: return@put
                docs.upload(user, call.parameters["name"].orEmpty(), call.parameters["file"].orEmpty(), bytes)
                    ?.let { return@put call.respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", it) }.toString()) }
                call.respondJson(HttpStatusCode.OK, """{"ok":true,"indexing":true}""")
            }
            delete("/collections/{name}/documents/{file}") {
                val user = call.principal(deps) ?: return@delete
                val docs = deps.documents ?: return@delete call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"문서 올리기를 쓸 수 없습니다"}""")
                docs.deleteDocument(user, call.parameters["name"].orEmpty(), call.parameters["file"].orEmpty())
                    ?.let { return@delete call.respondJson(HttpStatusCode.NotFound, buildJsonObject { put("error", it) }.toString()) }
                call.respondJson(HttpStatusCode.OK, """{"ok":true}""")
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
private const val VIDEO_MP4 = "video/mp4"

/** `Range: bytes=a-b` 하나만 (여러 범위는 전체로 답한다). 범위가 틀리면 null → 전체 */
internal object MediaRange {
    private val single = Regex("^bytes=(\\d*)-(\\d*)$")

    fun parse(header: String?, size: Int): IntRange? {
        val m = single.find(header?.trim() ?: return null) ?: return null
        val (a, b) = m.destructured
        if (size == 0 || (a.isEmpty() && b.isEmpty())) return null
        val range = if (a.isEmpty()) {
            val tail = b.toLongOrNull()?.coerceAtMost(size.toLong())?.toInt() ?: return null
            (size - tail) until size
        } else {
            val start = a.toLongOrNull()?.takeIf { it < size }?.toInt() ?: return null
            val end = (b.toLongOrNull()?.coerceAtMost(size - 1L)?.toInt() ?: (size - 1))
            if (end < start) return null
            start..end
        }
        return range.takeIf { !it.isEmpty() }
    }
}

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
    attributes.contains(PUBLIC_REQUEST) || request.origin.scheme == "https" || request.header("X-Forwarded-Proto") == "https" || request.host().endsWith(".ts.net")

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
    publicMismatch(deps, session.user)?.let {
        respondJson(HttpStatusCode.Forbidden, buildJsonObject { put("error", it) }.toString())
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

/** 바깥 입구로 온 요청 (M26). [email]은 Cloudflare Access 이메일 (직접 모드는 null) */
private class PublicRequest(val email: String?, val ip: String)
private val PUBLIC_REQUEST = io.ktor.util.AttributeKey<PublicRequest>("vesta-public")
internal const val ACCESS_HEADER = "Cf-Access-Jwt-Assertion"

/**
 * 바깥 요청의 IP. 입구는 127.0.0.1에만 열려 앞단(cloudflared·Caddy)만 붙는다. 그 모드의 앞단이 직접 적는 헤더 하나만 믿는다:
 * - 직접 모드: Caddy의 `X-Forwarded-For` (Caddy는 받은 값을 버리고 실제 접속 IP를 적는다). `Cf-Connecting-IP`는 바깥에서 꾸밀 수 있어 보지 않는다
 * - cloudflare 모드: `Cf-Connecting-IP` (Cloudflare가 덮어쓴다). `X-Forwarded-For`는 보지 않는다
 * 둘 다 바꿔 가며 보내 IP 제한을 피하지 못하게 (보안 검토)
 */
private fun ApplicationCall.clientIp(direct: Boolean): String =
    request.header(if (direct) "X-Forwarded-For" else "Cf-Connecting-IP")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteAddress

/** 바깥에서 로그인한 계정의 이메일이 Access 이메일과 다르면 안내 글. 이 Mac·Tailscale에서 온 요청이면 null */
private suspend fun ApplicationCall.publicMismatch(deps: ServerDeps, user: UserContext): String? {
    val access = attributes.getOrNull(PUBLIC_REQUEST)?.email ?: return null
    val email = deps.userEmail(user.userId)
    if (email != null && email.equals(access, ignoreCase = true)) return null
    return "이 Cloudflare 로그인($access)은 이 Vesta 계정에 연결돼 있지 않습니다. Mac 주인에게 vesta user email 로 연결을 부탁하세요"
}

/** 바깥 로그인: 로그인하려는 계정(이름을 비우면 Mac 주인)의 이메일이 Access 이메일과 같은지. 이 Mac·Tailscale에서 온 요청이면 true */
private suspend fun ApplicationCall.publicLoginAllowed(deps: ServerDeps, name: String?): Boolean {
    val access = attributes.getOrNull(PUBLIC_REQUEST)?.email ?: return true
    val login = name?.trim()?.takeIf { it.isNotBlank() }
    val id = if (login == null) deps.owner.userId else deps.users?.find(login)?.id ?: return false
    return deps.userEmail(id)?.equals(access, ignoreCase = true) == true
}

/**
 * 관리 기능(사용자·설정·학습·자가 개선·A2A)은 Mac 주인만, 바깥(터널)에서는 `publicAccess.ownerAdmin`일 때만 (P-06).
 * 안 되면 응답을 보내고 false
 */
private suspend fun ApplicationCall.manages(user: UserContext, deps: ServerDeps, message: String): Boolean {
    val error = when {
        !dev.vesta.identity.RolePolicy.canManage(user.role) -> message
        attributes.contains(PUBLIC_REQUEST) && deps.publicGate?.ownerAdmin != true -> "관리 기능은 이 Mac(또는 Tailscale)에서만 쓸 수 있습니다"
        else -> null
    } ?: return true
    respondJson(HttpStatusCode.Forbidden, buildJsonObject { put("error", error) }.toString())
    return false
}

/** 사용자 관리는 Mac 주인만 (U-01). 없거나 권한이 없으면 응답을 보내고 null */
private suspend fun ApplicationCall.usersFor(user: UserContext, deps: ServerDeps): dev.vesta.identity.UserService? {
    val users = deps.users ?: run { respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"사용자 관리가 꺼져 있음"}"""); return null }
    if (!manages(user, deps, "사용자 관리는 Mac 주인만 할 수 있습니다")) return null
    return users
}

private fun roleOf(name: String): dev.vesta.core.UserRole =
    runCatching { dev.vesta.core.UserRole.valueOf(name.uppercase()) }.getOrNull() ?: throw dev.vesta.identity.UserException("역할은 MEMBER·GUEST 중 하나입니다")

/** 사용자 관리 동작: 규칙 위반(UserException)은 400, 결과가 있으면 그것을, 없으면 ok */
private suspend fun ApplicationCall.userAction(block: suspend () -> UserDto?) {
    val result = try {
        block()
    } catch (e: dev.vesta.identity.UserException) {
        return respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", e.message.orEmpty()) }.toString())
    }
    respondJson(HttpStatusCode.OK, result?.let { ApiJson.encodeToString(UserDto.serializer(), it) } ?: """{"ok":true}""")
}

/** 기억은 GUEST가 아닌 사용자 본인 것만 (MEM-06). 없거나 손님이면 응답을 보내고 null */
private suspend fun ApplicationCall.schedulesFor(user: UserContext, deps: ServerDeps): Pair<dev.vesta.scheduler.Scheduler, dev.vesta.core.schedule.ScheduleRepository>? {
    val s = deps.schedules
    val r = deps.scheduleRepo
    if (s == null || r == null) { respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"예약을 쓸 수 없습니다"}"""); return null }
    if (user.role == UserRole.GUEST) { respondJson(HttpStatusCode.Forbidden, """{"error":"손님 계정은 예약을 쓸 수 없습니다"}"""); return null }
    return s to r
}

private suspend fun ApplicationCall.memoriesFor(user: UserContext, deps: ServerDeps): dev.vesta.memory.MemoryService? {
    val m = deps.memories ?: run { respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"기억 기능을 쓸 수 없습니다"}"""); return null }
    if (user.role == UserRole.GUEST) { respondJson(HttpStatusCode.Forbidden, """{"error":"손님 계정은 기억 기능을 쓰지 않습니다"}"""); return null }
    return m
}

private fun memoryKind(name: String): dev.vesta.core.memory.MemoryKind? = runCatching { dev.vesta.core.memory.MemoryKind.valueOf(name.uppercase()) }.getOrNull()

private fun dev.vesta.core.memory.Memory.dto() = dev.vesta.api.MemoryDto(id, text, kind.name, source.name, updatedAt.toString(), uses)

private suspend fun ApplicationCall.memorySaved(saved: dev.vesta.memory.MemoryService.Saved) = when (saved) {
    is dev.vesta.memory.MemoryService.Saved.New -> respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.MemoryDto.serializer(), saved.memory.dto()))
    is dev.vesta.memory.MemoryService.Saved.Replaced -> respondJson(HttpStatusCode.OK, ApiJson.encodeToString(dev.vesta.api.MemoryDto.serializer(), saved.memory.dto()))
    is dev.vesta.memory.MemoryService.Saved.Refused -> respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", saved.reason) }.toString())
}

/** 학습은 Mac 주인만 (7.12). 없거나 권한이 없으면 응답을 보내고 null */
private suspend fun ApplicationCall.messengersFor(user: UserContext, deps: ServerDeps): MessengerEndpoints? {
    val m = deps.messengers ?: run { respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"메신저가 설정돼 있지 않습니다"}"""); return null }
    if (!manages(user, deps, "다른 사용자의 메신저 연결은 Mac 주인만 관리할 수 있습니다")) return null
    return m
}

/** 경로의 메신저 이름. 켜진 메신저가 아니면 응답하고 null */
private suspend fun ApplicationCall.messengerOf(m: MessengerEndpoints): ChannelType? {
    val channel = MessengerEndpoints.MESSENGERS.firstOrNull { it.name == parameters["channel"] }
    if (channel == null || channel !in m.enabled()) { respondJson(HttpStatusCode.NotFound, """{"error":"켜져 있지 않은 메신저입니다 (봇 토큰을 먼저 저장하세요)"}"""); return null }
    return channel
}

private suspend fun ApplicationCall.evolutionFor(user: UserContext, deps: ServerDeps): EvolutionDesk? {
    val desk = deps.evolution ?: run { respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"자가 개선이 설정돼 있지 않습니다"}"""); return null }
    if (!manages(user, deps, "설정 변경은 Mac 주인만 할 수 있습니다")) return null
    return desk
}

private suspend fun ApplicationCall.trainingFor(user: UserContext, deps: ServerDeps): TrainingEndpoints? {
    val training = deps.training ?: run { respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"학습이 설정돼 있지 않습니다"}"""); return null }
    if (!manages(user, deps, "학습은 Mac 주인만 할 수 있습니다")) return null
    return training
}

/** Bearer 토큰 확인 (시간 차 공격을 피하려고 고정 시간 비교) */
private suspend fun ApplicationCall.bearer(token: String): Boolean {
    val given = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim().orEmpty()
    val ok = MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    if (!ok) respondJson(HttpStatusCode.Unauthorized, """{"error":"토큰이 필요합니다 (Authorization: Bearer …)"}""")
    return ok
}

private fun dev.vesta.core.studio.AgentRun.dto() = dev.vesta.api.RunDto(id, agent, customAgent, input.take(200), status.name, startedAt.toString(), finishedAt?.toString(), session?.value)
private fun dev.vesta.core.studio.RunEvent.dto() = dev.vesta.api.RunEventDto(run, seq, at.toString(), kind, title, detail)

/** 에이전트 스튜디오 (M22, A-10·A-11): 기본·사용자 정의 에이전트, 줄 수 있는 도구, 실행 기록 */
class StudioEndpoints(
    val agentRepo: dev.vesta.core.studio.CustomAgentRepository,
    val runRepo: dev.vesta.core.studio.RunRepository,
    val updates: kotlinx.coroutines.flow.Flow<dev.vesta.app.RunUpdate>,
    /** 이 사용자가 쓸 수 있는 도구 */
    val toolCatalog: suspend (UserContext) -> List<dev.vesta.core.tools.Tool>,
    val teamRepo: dev.vesta.core.studio.TeamRepository? = null,
    /** A2A (A-15). 없으면 A2A 에이전트를 쓰지 않는다 */
    val a2aRepo: dev.vesta.core.studio.A2aAgentRepository? = null,
    val a2aClient: dev.vesta.a2a.A2aClient? = null,
    val a2aHasToken: (String) -> Boolean = { false },
    /** 스킬 (SK-01): 웹에서 만든 것 저장소, 이 사용자가 부를 수 있는 전체(파일 포함) */
    val skillRepo: dev.vesta.core.skill.SkillRepository? = null,
    val skillsFor: suspend (UserContext) -> List<dev.vesta.core.skill.Skill> = { u -> skillRepo?.list(u.userId).orEmpty() },
) {
    suspend fun agents(user: UserContext): dev.vesta.api.AgentsDto {
        val builtin = BUILTIN.filter { (type, _, _) -> type in dev.vesta.identity.RolePolicy.agents(user.role) }
            .map { (type, label, description) -> dev.vesta.api.BuiltinAgentDto(type.name, label, description) }
        val custom = agentRepo.list(user.userId).map { dev.vesta.api.CustomAgentDto(it.name, it.description, it.instructions, it.model.name, it.tools, it.updatedAt.toString()) }
        val tools = toolCatalog(user).map { dev.vesta.api.ToolInfoDto(it.spec.name, it.risk.name, it.spec.description) }
        val teams = teamRepo?.list(user.userId).orEmpty().map { dev.vesta.api.TeamDto(it.name, it.description, it.leader, it.members, it.updatedAt.toString()) }
        val owner = user.role == UserRole.OWNER && a2aRepo != null && a2aClient != null
        val a2a = if (owner) a2aRepo!!.list(user.userId).map {
            dev.vesta.api.A2aAgentDto(it.name, it.displayName, it.description, it.endpoint, it.protocol, it.streaming, it.auth, it.local, it.auth && a2aHasToken(it.name), it.skills, it.cardUrl)
        } else emptyList()
        fun dev.vesta.core.skill.Skill.dto() = dev.vesta.api.SkillDto(name, description, body, agent.name, this.tools, source.name, auto, run)
        val skills = skillsFor(user).map { it.dto() }
        val drafts = skillRepo?.drafts(user.userId).orEmpty().map { it.dto() }
        return dev.vesta.api.AgentsDto(builtin, custom, tools, teams, a2a, owner, skills, drafts)
    }

    /** 스킬 저장 (웹). 이름 규칙·예약어, 그 역할이 쓸 수 있는 에이전트, 쓸 수 있는 도구만, 숨은 문자 거부 (SK-06). 같은 이름의 초안이면 승인 (SK-11). 문제가 있으면 그 이유 */
    suspend fun saveSkill(user: UserContext, dto: dev.vesta.api.SkillDto): String? {
        val repo = skillRepo ?: return "스킬을 쓸 수 없습니다"
        val s = dev.vesta.core.skill.Skill
        s.problem(dto.name, dto.description, dto.body, dto.agent, dto.tools, dev.vesta.identity.RolePolicy.agents(user.role), toolCatalog(user).map { it.spec.name }.toSet())?.let { return it }
        val agent = dev.vesta.core.AgentType.valueOf(dto.agent)
        val tools = if (agent == dev.vesta.core.AgentType.TASK) dto.tools.distinct() else emptyList()
        repo.save(user.userId, dev.vesta.core.skill.Skill(dto.name, dto.description.trim(), dto.body.trim(), agent, tools))
        return null
    }

    /** Agent Card를 읽어 등록 전에 보여 줄 내용과 경고 */
    suspend fun previewA2a(url: String, local: Boolean = false): Result<dev.vesta.api.A2aPreviewDto> = runCatching {
        val client = a2aClient ?: error("A2A를 쓸 수 없습니다")
        val card = try { client.card(url, local) } catch (e: dev.vesta.a2a.A2aException) { error(e.message ?: "카드를 읽지 못했습니다") }
        val cardHost = runCatching { java.net.URI(card.cardUrl).host }.getOrNull()
        val endpoint = java.net.URI(card.endpoint)
        val warnings = listOfNotNull(
            "내부 에이전트로 붙입니다: 이 Mac·내 네트워크의 ${endpoint.host}:${endpoint.port}에 연결합니다. 직접 띄운 에이전트가 맞을 때만 붙이세요".takeIf { local },
            "맡기는 할 일 내용이 이 에이전트(${endpoint.host})로 나갑니다. 대화 기록·파일은 보내지 않지만, 할 일에 적힌 내용은 그대로 보냅니다",
            "카드 주소(${cardHost})와 연결 주소(${endpoint.host})의 호스트가 다릅니다".takeIf { cardHost != null && cardHost != endpoint.host },
            "암호화되지 않은 http 연결입니다".takeIf { endpoint.scheme == "http" && !local },
            "인증이 필요한 에이전트입니다. 등록한 뒤 키체인에 토큰을 넣으세요: security add-generic-password -U -s dev.vesta.a2a -a <이름> -w".takeIf { card.auth },
        )
        val suggested = card.name.lowercase().replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-', '_').take(32)
            .takeIf { dev.vesta.core.studio.CustomAgentDef.NAME.matches(it) } ?: "remote"
        dev.vesta.api.A2aPreviewDto(suggested, card.name, card.description, card.endpoint, card.protocol, card.streaming, card.auth, card.skills, card.version, warnings)
    }

    /** 등록 (카드를 다시 읽어 저장). 이름은 사용자 정의 에이전트와 겹치면 안 된다 (`/agent 이름`으로 함께 부른다) */
    suspend fun saveA2a(user: UserContext, name: String, url: String, previewed: String?, local: Boolean = false): String? {
        val repo = a2aRepo ?: return "A2A를 쓸 수 없습니다"
        val client = a2aClient ?: return "A2A를 쓸 수 없습니다"
        if (!dev.vesta.core.studio.CustomAgentDef.NAME.matches(name)) return "이름은 글자·숫자로 시작하는 1~32자(글자·숫자·_-)여야 합니다 (/agent 이름 으로 부릅니다)"
        if (agentRepo.find(user.userId, name) != null) return "같은 이름의 사용자 정의 에이전트가 있습니다"
        val card = try { client.card(url, local) } catch (e: dev.vesta.a2a.A2aException) { return e.message ?: "카드를 읽지 못했습니다" }
        // 미리 보기에서 사용자가 본 연결 주소와 같아야 한다 (그사이 카드가 바뀌었으면 다시 확인하게)
        if (previewed == null || previewed != card.endpoint) return "미리 보기 뒤에 Agent Card의 연결 주소가 바뀌었습니다 (${card.endpoint}). 다시 카드를 읽어 확인해 주세요"
        repo.save(dev.vesta.core.studio.A2aAgentDef(user.userId, name, card.cardUrl, card.endpoint, card.protocol, card.name, card.description, card.skills, card.streaming, card.auth, card.local))
        return null
    }

    /** 팀 저장. 리더·멤버는 이 사용자의 사용자 정의 에이전트, 리더는 멤버가 아니다. 문제가 있으면 그 이유 */
    suspend fun saveTeam(user: UserContext, dto: dev.vesta.api.TeamDto): String? {
        val repo = teamRepo ?: return "팀을 쓸 수 없습니다"
        val d = dev.vesta.core.studio.CustomAgentDef
        if (!d.NAME.matches(dto.name)) return "이름은 글자·숫자로 시작하는 1~32자(글자·숫자·_-)여야 합니다 (/team 이름 으로 부릅니다)"
        if (!dev.vesta.identity.RolePolicy.agents(user.role).contains(dev.vesta.core.AgentType.TASK)) return "이 계정은 팀을 쓸 수 없습니다"
        if (dto.description.isBlank() || dto.description.length > d.MAX_DESCRIPTION) return "팀 설명은 1~${d.MAX_DESCRIPTION}자입니다"
        val members = dto.members.distinct()
        if (members.isEmpty() || members.size > dev.vesta.core.studio.TeamDef.MAX_MEMBERS) return "멤버는 1~${dev.vesta.core.studio.TeamDef.MAX_MEMBERS}명입니다"
        if (dto.leader in members) return "리더는 멤버가 될 수 없습니다"
        val mine = agentRepo.list(user.userId).map { it.name }.toSet()
        // 멤버로는 A2A 에이전트도 (Mac 주인만). 리더는 delegate 도구를 받아야 하므로 사용자 정의 에이전트만
        val remote = if (user.role == UserRole.OWNER) a2aRepo?.list(user.userId).orEmpty().map { it.name }.toSet() else emptySet()
        if (dto.leader !in mine) return if (dto.leader in remote) "리더는 사용자 정의 에이전트여야 합니다 (A2A 에이전트는 멤버로)" else "없는 에이전트: ${dto.leader}"
        val unknown = members.filter { it !in mine && it !in remote }
        if (unknown.isNotEmpty()) return "없는 에이전트: ${unknown.joinToString()}"
        repo.save(dev.vesta.core.studio.TeamDef(user.userId, dto.name, dto.description.trim(), dto.leader, members))
        return null
    }

    /** 저장. 문제가 있으면 그 이유 */
    suspend fun save(user: UserContext, dto: dev.vesta.api.CustomAgentDto): String? {
        val d = dev.vesta.core.studio.CustomAgentDef
        if (!d.NAME.matches(dto.name)) return "이름은 글자·숫자로 시작하는 1~32자(글자·숫자·_-)여야 합니다 (/agent 이름 으로 부릅니다)"
        if (!dev.vesta.identity.RolePolicy.agents(user.role).contains(dev.vesta.core.AgentType.TASK)) return "이 계정은 사용자 정의 에이전트를 쓸 수 없습니다"
        if (dto.description.isBlank() || dto.description.length > d.MAX_DESCRIPTION) return "역할 설명은 1~${d.MAX_DESCRIPTION}자입니다"
        if (dto.instructions.length > d.MAX_INSTRUCTIONS) return "지침은 ${d.MAX_INSTRUCTIONS}자까지입니다"
        val model = runCatching { dev.vesta.core.ModelRole.valueOf(dto.model) }.getOrNull()?.takeIf { it == dev.vesta.core.ModelRole.GENERAL || it == dev.vesta.core.ModelRole.CODE }
            ?: return "모델은 GENERAL 또는 CODE입니다"
        // 권한을 넓히지 못한다: 이 사용자가 쓸 수 있는 도구만
        val allowed = toolCatalog(user).map { it.spec.name }.toSet()
        val unknown = dto.tools.filter { it !in allowed }
        if (unknown.isNotEmpty()) return "쓸 수 없는 도구: ${unknown.joinToString()}"
        if (a2aRepo?.find(user.userId, dto.name) != null) return "같은 이름의 A2A 에이전트가 있습니다"
        agentRepo.save(dev.vesta.core.studio.CustomAgentDef(user.userId, dto.name, dto.description.trim(), dto.instructions, model, dto.tools.distinct()))
        return null
    }

    private companion object {
        val BUILTIN = listOf(
            Triple(dev.vesta.core.AgentType.CHAT, "대화", "일반 대화·설명·글쓰기·번역"),
            Triple(dev.vesta.core.AgentType.DOCS, "문서", "등록한 문서 컬렉션에서 찾아 출처와 함께 답한다"),
            Triple(dev.vesta.core.AgentType.WEB, "웹 리서치", "웹을 검색해 출처와 함께 답한다"),
            Triple(dev.vesta.core.AgentType.CODE, "코드 도우미", "등록한 저장소에서 찾아 파일·줄과 함께 답하고 수정안을 낸다"),
            Triple(dev.vesta.core.AgentType.TASK, "작업", "파일·셸 도구로 직접 처리한다 (쓰기·셸은 승인)"),
            Triple(dev.vesta.core.AgentType.PERSONAL, "개인 비서", "Apple 앱(일정·메일·미리 알림 등, Orchard)"),
            Triple(dev.vesta.core.AgentType.IMAGE, "이미지", "그림을 만든다 (FLUX)"),
        )
    }
}
