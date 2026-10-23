package dev.vesta.a2a

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/** 읽은 Agent Card 중 쓰는 것 */
data class A2aCard(
    val cardUrl: String,
    val name: String,
    val description: String,
    /** JSON-RPC 주소 */
    val endpoint: String,
    /** "1.0" 또는 "0.3" */
    val protocol: String,
    val streaming: Boolean,
    val auth: Boolean,
    val skills: List<String>,
    val version: String?,
    /** 연결 주소가 내부 주소(이 Mac·사설망·Tailscale)로 해석되는가 */
    val local: Boolean = false,
)

/** 원격 에이전트가 보낸 소식 (두 버전을 같은 모양으로) */
sealed interface A2aUpdate {
    /** 진행 상황 한 줄 */
    data class Status(val state: String, val text: String?) : A2aUpdate
    /** 답 글 (결과물·메시지) */
    data class Text(val text: String) : A2aUpdate
    /** 끝. [state]: completed·failed·canceled·rejected·input-required·auth-required */
    data class Finished(val state: String, val text: String?) : A2aUpdate
}

class A2aException(message: String) : RuntimeException(message)

/**
 * A2A(Agent2Agent) 클라이언트 (PROJECT.md A-15, M22). JSON-RPC 바인딩만 쓴다.
 * - 1.0: `SendMessage`·`SendStreamingMessage`, `A2A-Version: 1.0`, `ROLE_USER`, `{"text": …}` 조각, `TASK_STATE_*`
 * - 0.3: `message/send`·`message/stream`, `"kind"` 구분자, `user`, 소문자 상태
 * 카드·응답 크기와 답 길이에 한도를 둔다 (바깥 서버를 믿지 않는다)
 */
class A2aClient(
    private val http: HttpClient,
    private val maxCardBytes: Long = 256_000,
    private val maxResponseBytes: Long = 2_000_000,
    private val maxAnswerChars: Int = 20_000,
    private val requestTimeoutMillis: Long = 600_000,
    private val resolve: suspend (String) -> List<java.net.InetAddress> = { host ->
        withContext(kotlinx.coroutines.Dispatchers.IO) { java.net.InetAddress.getAllByName(host).toList() }
    },
    /** 내부 주소인가 (앱에서는 웹 수집과 같은 UrlGuard.isInternal) */
    private val internal: (java.net.InetAddress) -> Boolean = { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress },
) {
    private val ids = AtomicLong()

    /** 주소에서 Agent Card를 읽는다. 카드 주소(…json)가 아니면 `/.well-known/agent-card.json`, 다음 `/.well-known/agent.json`(0.3 이전) */
    suspend fun card(address: String): A2aCard {
        val url = runCatching { Url(address.trim()) }.getOrNull()?.takeIf { it.protocol.name == "http" || it.protocol.name == "https" }
            ?: throw A2aException("http(s) 주소가 아닙니다")
        val candidates = if (url.encodedPath.endsWith(".json")) listOf(url.toString())
            else listOf("agent-card.json", "agent.json").map { file -> io.ktor.http.URLBuilder(url).apply { pathSegments = listOf("", ".well-known", file); parameters.clear(); fragment = "" }.buildString() }
        var lastError = "카드를 찾지 못했습니다"
        for (candidate in candidates) {
            val response = runCatching { http.get(candidate) { timeout { requestTimeoutMillis = 15_000 } } }.getOrElse { lastError = "연결 실패: ${it.message}"; continue }
            if (!response.status.isSuccess()) { lastError = "$candidate: HTTP ${response.status.value}"; continue }
            val bytes = response.bodyAsChannel().readRemaining(maxCardBytes + 1).readByteArray()
            if (bytes.size > maxCardBytes) throw A2aException("Agent Card가 너무 큽니다")
            val json = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrElse { throw A2aException("Agent Card가 JSON이 아닙니다") }
            return checkEndpoint(parseCard(candidate, json))
        }
        throw A2aException(lastError)
    }

    /**
     * 카드가 정한 연결 주소 검사 (SSRF 방지, M22 보안 검토). 바깥 카드가 이 Mac·사설망의 다른 서비스(Ollama 등)로 보내게 만들지 못하게:
     * 연결 주소가 내부 주소면 카드와 같은 호스트여야 한다 (사용자가 직접 넣은 로컬 에이전트는 된다)
     */
    private suspend fun checkEndpoint(card: A2aCard): A2aCard {
        val endpointHost = Url(card.endpoint).host
        val local = isInternal(endpointHost) ?: throw A2aException("연결 주소를 찾을 수 없습니다: $endpointHost")
        if (local && endpointHost != Url(card.cardUrl).host) throw A2aException("카드가 다른 호스트의 내부 주소($endpointHost)로 연결하게 합니다. 이런 에이전트는 붙이지 않습니다")
        return card.copy(local = local)
    }

    /** 해석한 IP 중 하나라도 내부면 true, 찾지 못하면 null */
    private suspend fun isInternal(host: String): Boolean? {
        val addresses = runCatching { resolve(host) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return addresses.any(internal)
    }

    /** 할 일을 보내고 소식을 흘린다. 흐름이 취소되면 원격 작업 취소를 한 번 보낸다 */
    fun send(card: A2aCard, text: String, token: String?): Flow<A2aUpdate> = flow {
        // 등록 때 바깥 주소였는데 지금 내부로 해석되면 보내지 않는다 (DNS rebinding). 확인과 연결 사이의 바뀜은 남은 위험 (UrlGuard와 같음)
        if (!card.local) {
            val host = Url(card.endpoint).host
            if (isInternal(host) != false) throw A2aException("연결 주소 $host 가 등록 때와 달리 내부 주소로 해석되거나 찾을 수 없어 보내지 않았습니다")
        }
        val v1 = card.protocol == "1.0"
        val method = when {
            v1 && card.streaming -> "SendStreamingMessage"
            v1 -> "SendMessage"
            card.streaming -> "message/stream"
            else -> "message/send"
        }
        val message = buildJsonObject {
            put("messageId", UUID.randomUUID().toString())
            if (v1) put("role", "ROLE_USER") else { put("kind", "message"); put("role", "user") }
            putJsonArray("parts") { add(buildJsonObject { if (!v1) put("kind", "text"); put("text", text) }) }
        }
        val body = rpc(method, buildJsonObject { put("message", message) })
        val state = Progress()
        try {
            post(card, body, token) { response ->
                if (card.streaming && response.contentType()?.match(ContentType.Text.EventStream) == true) readEvents(response, state)
                else handleResult(rpcResult(Json.parseToJsonElement(readLimited(response)).jsonObject), state)
            }
            if (state.finished == null) finish(state, "completed", null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            state.taskId?.let { id -> withContext(NonCancellable) { withTimeoutOrNull(5_000) { runCatching { cancel(card, id, token) } } } }
            throw e
        }
    }

    private class Progress {
        var taskId: String? = null
        var chars = 0
        var finished: String? = null
        val artifacts = mutableSetOf<String>()
        var lastStatus: String? = null
    }

    private suspend fun cancel(card: A2aCard, taskId: String, token: String?) {
        val v1 = card.protocol == "1.0"
        post(card, rpc(if (v1) "CancelTask" else "tasks/cancel", buildJsonObject { put("id", taskId) }), token) { readLimited(it) }
    }

    private fun rpc(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", ids.incrementAndGet())
        put("method", method)
        put("params", params)
    }

    private suspend fun <T> post(card: A2aCard, body: JsonObject, token: String?, block: suspend (HttpResponse) -> T): T =
        http.preparePost(card.endpoint) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            if (card.protocol == "1.0") header("A2A-Version", "1.0")
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body.toString())
            timeout { requestTimeoutMillis = this@A2aClient.requestTimeoutMillis; socketTimeoutMillis = 180_000 }
        }.execute { response ->
            if (response.status.value == 401 || response.status.value == 403) throw A2aException("인증 실패 (HTTP ${response.status.value}): 키체인 dev.vesta.a2a 토큰을 확인하세요")
            if (!response.status.isSuccess()) throw A2aException("HTTP ${response.status.value}")
            block(response)
        }

    private suspend fun readLimited(response: HttpResponse): String {
        val bytes = response.bodyAsChannel().readRemaining(maxResponseBytes + 1).readByteArray()
        if (bytes.size > maxResponseBytes) throw A2aException("응답이 너무 큽니다")
        return bytes.decodeToString()
    }

    /** SSE: `data:` 줄을 빈 줄까지 모아 JSON-RPC 응답 하나로 */
    private suspend fun FlowCollector<A2aUpdate>.readEvents(response: HttpResponse, state: Progress) {
        val channel = response.bodyAsChannel()
        val data = StringBuilder()
        var total = 0L
        while (state.finished == null) {
            // 줄 하나도 한도 안에서만 읽는다 (끝없는 한 줄로 메모리를 채우지 못하게)
            val line = try { channel.readUTF8Line((maxResponseBytes - total).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw A2aException("응답이 너무 큽니다") } ?: break
            total += line.length + 1
            if (total > maxResponseBytes) throw A2aException("응답이 너무 큽니다")
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                line.isBlank() && data.isNotEmpty() -> {
                    val json = runCatching { Json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                    data.clear()
                    json?.let { handleResult(rpcResult(it), state) }
                }
            }
        }
        if (data.isNotEmpty() && state.finished == null) runCatching { Json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()?.let { handleResult(rpcResult(it), state) }
    }

    private fun rpcResult(json: JsonObject): JsonObject {
        (json["error"] as? JsonObject)?.let { error ->
            throw A2aException("원격 에이전트 오류 ${error.str("code").orEmpty()}: ${error.str("message").orEmpty().take(300)}")
        }
        return json["result"] as? JsonObject ?: throw A2aException("JSON-RPC 결과가 없습니다")
    }

    /** 결과 하나: 1.0은 `{task|message|statusUpdate|artifactUpdate: …}`, 0.3은 `kind` 필드 */
    private suspend fun FlowCollector<A2aUpdate>.handleResult(result: JsonObject, state: Progress) {
        val (kind, body) = when {
            result["task"] is JsonObject -> "task" to result.obj("task")!!
            result["message"] is JsonObject -> "message" to result.obj("message")!!
            result["statusUpdate"] is JsonObject -> "status-update" to result.obj("statusUpdate")!!
            result["artifactUpdate"] is JsonObject -> "artifact-update" to result.obj("artifactUpdate")!!
            else -> (result.str("kind") ?: "task") to result
        }
        when (kind) {
            "message" -> {
                text(state, partsText(body["parts"]))
                finish(state, "completed", null)
            }
            "task" -> {
                body.str("id")?.let { state.taskId = it }
                (body["artifacts"] as? JsonArray)?.forEach { artifact -> artifact(state, artifact as? JsonObject ?: return@forEach) }
                status(state, body.obj("status"))
            }
            "status-update" -> {
                body.str("taskId")?.let { state.taskId = it }
                status(state, body.obj("status"))
                if ((body["final"] as? JsonPrimitive)?.booleanOrNull == true && state.finished == null) finish(state, normalize(body.obj("status")?.str("state")), null)
            }
            "artifact-update" -> {
                body.str("taskId")?.let { state.taskId = it }
                body.obj("artifact")?.let { artifact(state, it) }
            }
        }
    }

    private suspend fun FlowCollector<A2aUpdate>.artifact(state: Progress, artifact: JsonObject) {
        val id = artifact.str("artifactId") ?: artifact.str("name") ?: ""
        val text = partsText(artifact["parts"])
        if (text.isEmpty()) return
        // 새 결과물이면 앞 결과물과 줄을 띄운다
        if (state.artifacts.add(id) && state.chars > 0) text(state, "\n\n")
        text(state, text)
    }

    private suspend fun FlowCollector<A2aUpdate>.status(state: Progress, status: JsonObject?) {
        status ?: return
        val value = normalize(status.str("state"))
        val message = status.obj("message")?.let { partsText(it["parts"]) }?.ifBlank { null }
        when (value) {
            "completed", "failed", "canceled", "rejected", "input-required", "auth-required" -> finish(state, value, message)
            else -> if (message != state.lastStatus || value != "working") {
                state.lastStatus = message
                emit(A2aUpdate.Status(value, message))
            }
        }
    }

    private suspend fun FlowCollector<A2aUpdate>.finish(state: Progress, value: String, message: String?) {
        if (state.finished != null) return
        state.finished = value
        // 끝 상태의 메시지는 결과물이 없을 때 답으로 쓴다
        if (message != null && state.chars == 0) text(state, message)
        emit(A2aUpdate.Finished(value, message))
    }

    private suspend fun FlowCollector<A2aUpdate>.text(state: Progress, text: String) {
        if (text.isEmpty() || state.chars >= maxAnswerChars) return
        val piece = text.take(maxAnswerChars - state.chars)
        state.chars += piece.length
        emit(A2aUpdate.Text(piece))
        if (state.chars >= maxAnswerChars) emit(A2aUpdate.Text("\n…(원격 답이 길어 ${maxAnswerChars}자까지만)"))
    }

    companion object {
        /** `TASK_STATE_INPUT_REQUIRED` → `input-required`, `completed` → `completed` */
        fun normalize(state: String?): String = state.orEmpty().removePrefix("TASK_STATE_").lowercase().replace('_', '-').ifEmpty { "working" }

        /** 글 조각만 잇는다 (파일·데이터 조각은 종류만 알린다) */
        fun partsText(parts: JsonElement?): String = (parts as? JsonArray).orEmpty().mapNotNull { part ->
            val p = part as? JsonObject ?: return@mapNotNull null
            p.str("text") ?: when {
                p["file"] != null || p["raw"] != null || p["url"] != null -> "[파일]"
                p["data"] != null -> "[데이터] " + p["data"].toString().take(2_000)
                else -> null
            }
        }.joinToString("")

        /** 카드에서 JSON-RPC 연결을 고른다. 1.0 `supportedInterfaces`, 0.3 `url`·`preferredTransport`·`additionalInterfaces` */
        fun parseCard(cardUrl: String, json: JsonObject): A2aCard {
            val interfaces = (json["supportedInterfaces"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val (endpoint, protocol) = interfaces.firstOrNull { it.str("protocolBinding").equals("JSONRPC", ignoreCase = true) }
                ?.let { (it.str("url") ?: "") to version(it.str("protocolVersion") ?: "1.0") }
                ?: json.str("url")?.takeIf { (json.str("preferredTransport") ?: "JSONRPC").equals("JSONRPC", ignoreCase = true) }
                    ?.let { it to version(json.str("protocolVersion") ?: "0.3") }
                ?: (json["additionalInterfaces"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .firstOrNull { it.str("transport").equals("JSONRPC", ignoreCase = true) }
                    ?.let { (it.str("url") ?: "") to version(json.str("protocolVersion") ?: "0.3") }
                ?: throw A2aException(if (interfaces.isNotEmpty() || json["url"] != null) "JSON-RPC 연결이 없는 에이전트입니다 (gRPC·HTTP+JSON만 지원)" else "Agent Card에 연결 주소가 없습니다")
            val url = runCatching { Url(endpoint) }.getOrNull()?.takeIf { (it.protocol.name == "http" || it.protocol.name == "https") && it.host.isNotEmpty() }
                ?: throw A2aException("연결 주소가 http(s)가 아닙니다: ${endpoint.take(200)}")
            val capabilities = json.obj("capabilities")
            val schemes = json.obj("securitySchemes")
            return A2aCard(
                cardUrl = cardUrl,
                name = json.str("name")?.take(100) ?: url.host,
                description = json.str("description")?.take(1_000).orEmpty(),
                endpoint = url.toString(),
                protocol = protocol,
                streaming = (capabilities?.get("streaming") as? JsonPrimitive)?.booleanOrNull == true,
                auth = schemes != null && schemes.isNotEmpty(),
                skills = (json["skills"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(30).map { skill ->
                    listOfNotNull(skill.str("name") ?: skill.str("id"), skill.str("description")?.take(200)).joinToString(" — ")
                },
                version = json.str("version")?.take(40),
            )
        }

        private fun version(value: String) = if (value.startsWith("0.")) "0.3" else "1.0"
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content
        private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    }
}
