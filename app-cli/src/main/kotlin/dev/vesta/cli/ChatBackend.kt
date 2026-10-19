package dev.vesta.cli

import dev.vesta.api.ApiJson
import dev.vesta.api.ApprovalDto
import dev.vesta.api.ChatRequestDto
import dev.vesta.api.EventDto
import dev.vesta.api.HealthDto
import dev.vesta.api.MessageDto
import dev.vesta.api.SessionDto
import dev.vesta.api.toEvent
import dev.vesta.app.AppContainer
import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.Session
import dev.vesta.core.session.StoredMessage
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.config.VestaPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Instant

/**
 * CLI 대화가 쓰는 백엔드 (PROJECT.md 6.2: CLI는 서버의 클라이언트, 서버가 없으면 임베디드 모드).
 */
interface ChatBackend : AutoCloseable {
    /** 시작 줄에 보여 줄 설명 */
    val description: String
    val home: Path
    fun send(sessionId: SessionId?, text: String): Flow<AgentEvent>
    suspend fun resolveApproval(requestId: String, decision: ApprovalDecision)
    suspend fun recent(limit: Int): List<Session>
    suspend fun find(id: SessionId): Session?
    suspend fun messages(id: SessionId): List<StoredMessage>
}

/** 같은 프로세스 안에서 바로 실행 */
class EmbeddedBackend(private val app: AppContainer) : ChatBackend {
    override val description: String = app.config.models.general?.let { g ->
        "모델 ${g.name}${g.reasoningEffort?.let { " (생각 $it)" }.orEmpty()} · 임베디드"
    } ?: "임베디드"
    override val home: Path = app.paths.home
    override fun send(sessionId: SessionId?, text: String) = app.conversation.send(app.owner, ChannelType.CLI, sessionId, UserInput(text))
    override suspend fun resolveApproval(requestId: String, decision: ApprovalDecision) {
        app.conversation.resolveApproval(app.owner, requestId, decision)
    }
    override suspend fun recent(limit: Int) = app.sessions.listRecent(app.owner.userId, limit)
    override suspend fun find(id: SessionId) = app.sessions.find(app.owner.userId, id)
    override suspend fun messages(id: SessionId) = app.messages.list(id)
    override fun close() = app.close()
}

/** 상시 실행 서버(app-server)에 HTTP로 붙는다 */
class RemoteBackend(private val baseUrl: String, private val token: String, version: String) : ChatBackend {
    private val http = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = Long.MAX_VALUE // 스트리밍·승인 대기
            socketTimeoutMillis = 15 * 60 * 1_000L
        }
    }
    override val description = "서버 $baseUrl (v$version)"
    override val home: Path = VestaPaths.default().home

    override fun send(sessionId: SessionId?, text: String): Flow<AgentEvent> = flow {
        http.preparePost("$baseUrl/api/chat") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(ChatRequestDto.serializer(), ChatRequestDto(text, sessionId?.value, channel = "CLI")))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                emit(AgentEvent.Error("서버 오류 ${response.status.value}: ${response.bodyAsText().take(200)}"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                emit(ApiJson.decodeFromString(EventDto.serializer(), line.removePrefix("data:").trim()).toEvent())
            }
        }
    }

    override suspend fun resolveApproval(requestId: String, decision: ApprovalDecision) {
        val dto = when (decision) {
            ApprovalDecision.Approve -> ApprovalDto("approve")
            is ApprovalDecision.Deny -> ApprovalDto("deny", decision.reason)
            is ApprovalDecision.ApproveEdited -> ApprovalDto("edit", edited = decision.editedArguments.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() })
        }
        http.post("$baseUrl/api/approvals/$requestId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ApiJson.encodeToString(ApprovalDto.serializer(), dto))
        }
    }

    override suspend fun recent(limit: Int): List<Session> =
        get("/api/sessions?limit=$limit")?.let { ApiJson.decodeFromString(ListSerializer(SessionDto.serializer()), it) }.orEmpty().map { it.toSession() }

    override suspend fun find(id: SessionId): Session? = recent(200).firstOrNull { it.id == id }

    override suspend fun messages(id: SessionId): List<StoredMessage> =
        get("/api/sessions/${id.value}/messages")?.let { ApiJson.decodeFromString(ListSerializer(MessageDto.serializer()), it) }.orEmpty()
            .mapIndexed { i, m ->
                StoredMessage(i.toLong(), id, ChatRole.valueOf(m.role), m.content, m.agent?.let(AgentType::valueOf), m.model, m.interrupted, Instant.parse(m.createdAt))
            }

    private suspend fun get(path: String): String? {
        val response = http.get(baseUrl + path) { bearerAuth(token) }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    private fun SessionDto.toSession() =
        Session(SessionId(id), UserId(0), title, ChannelType.valueOf(channel), null, Instant.parse(updatedAt), Instant.parse(updatedAt))

    override fun close() = http.close()

    companion object {
        /** 서버가 떠 있고 토큰이 있으면 원격 백엔드 */
        suspend fun connect(baseUrl: String, token: String?): RemoteBackend? {
            if (token == null) return null
            val probe = HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) { connectTimeoutMillis = 500; requestTimeoutMillis = 1_500 }
            }
            return probe.use { client ->
                withTimeoutOrNull(2_000) {
                    runCatching { client.get("$baseUrl/api/health") }.getOrNull()
                        ?.takeIf { it.status.isSuccess() }
                        ?.let { ApiJson.decodeFromString(HealthDto.serializer(), it.bodyAsText()) }
                }
            }?.let { RemoteBackend(baseUrl, token, it.version) }
        }
    }
}
