package dev.vesta.channels.slack

import dev.vesta.channels.ChannelApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Slack Web API 중 Vesta가 쓰는 부분 (PROJECT.md 6.4). 받기는 [SlackSocket](Socket Mode)이 한다.
 * 봇 토큰(xoxb-)으로 메시지를 보내고, 앱 토큰(xapp-)은 Socket Mode 연결 주소를 받을 때만 쓴다.
 */
interface SlackApi {
    suspend fun authTest(): SlackAuth
    /** Socket Mode WebSocket 주소 (앱 토큰) */
    suspend fun openConnection(): String
    /** 보낸 메시지의 ts(메시지 ID) */
    suspend fun postMessage(channel: String, text: String, blocks: JsonArray? = null): String
    /** [blocks]가 null이면 그대로 두고, 빈 배열이면 블록(버튼)을 없앤다 */
    suspend fun update(channel: String, ts: String, text: String, blocks: JsonArray? = null)
    /** [user]에게만 보이는 안내 */
    suspend fun postEphemeral(channel: String, user: String, text: String)
    /** 입력 창(모달). [triggerId]는 버튼을 누른 뒤 3초만 쓸 수 있다 */
    suspend fun openView(triggerId: String, view: JsonObject)
    /** 파일 올리기 (만든 이미지). files:write 권한이 필요하다 */
    suspend fun uploadFile(channel: String, fileName: String, bytes: ByteArray, title: String?)
    /** 첨부 파일 (files.slack.com만, 봇 토큰으로). [maxBytes]를 넘으면 받지 않는다 */
    suspend fun download(url: String, maxBytes: Long): ByteArray
}

data class SlackAuth(val userId: String, val user: String?, val team: String?)

/** Web API 오류. [error]는 Slack 오류 코드 (예: invalid_auth) */
class SlackException(val code: Int, val error: String, retryAfterMillis: Long? = null) :
    ChannelApiException("Slack $code: $error", retryAfterMillis) {
    /** 토큰이 틀렸거나 앱이 지워져 다시 붙어도 소용없음 */
    val fatal get() = error in FATAL_ERRORS

    private companion object {
        val FATAL_ERRORS = setOf("invalid_auth", "not_authed", "account_inactive", "token_revoked", "token_expired", "not_allowed_token_type")
    }
}

/** 429면 Retry-After만큼 기다렸다 다시 보낸다(10초 이하일 때 두 번까지) */
class HttpSlackApi(
    private val botToken: String,
    private val appToken: String,
    private val http: HttpClient = restClient(),
    private val baseUrl: String = "https://slack.com/api",
) : SlackApi {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun authTest(): SlackAuth {
        val result = call("auth.test", null, botToken)
        return SlackAuth(
            result["user_id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("auth.test: user_id 없음"),
            result["user"]?.jsonPrimitive?.contentOrNull,
            result["team"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun openConnection(): String =
        call("apps.connections.open", null, appToken)["url"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Socket Mode 주소 없음")

    override suspend fun postMessage(channel: String, text: String, blocks: JsonArray?): String {
        val result = call("chat.postMessage", buildJsonObject {
            put("channel", channel)
            put("text", text)
            blocks?.let { put("blocks", it) }
            put("unfurl_links", false)
            put("unfurl_media", false)
        }, botToken)
        return result["ts"]?.jsonPrimitive?.contentOrNull ?: throw IOException("chat.postMessage: ts 없음")
    }

    override suspend fun update(channel: String, ts: String, text: String, blocks: JsonArray?) {
        call("chat.update", buildJsonObject {
            put("channel", channel)
            put("ts", ts)
            put("text", text)
            blocks?.let { put("blocks", it) }
        }, botToken)
    }

    override suspend fun postEphemeral(channel: String, user: String, text: String) {
        call("chat.postEphemeral", buildJsonObject { put("channel", channel); put("user", user); put("text", text) }, botToken)
    }

    override suspend fun openView(triggerId: String, view: JsonObject) {
        call("views.open", buildJsonObject { put("trigger_id", triggerId); put("view", view) }, botToken)
    }

    /** 외부 업로드 3단계: 올릴 주소 받기 → 내용 보내기 → 대화방에 공유 */
    override suspend fun uploadFile(channel: String, fileName: String, bytes: ByteArray, title: String?) {
        val ticket = guarded {
            val response = http.submitForm(
                "$baseUrl/files.getUploadURLExternal",
                Parameters.build { append("filename", fileName); append("length", bytes.size.toString()) },
            ) { header(HttpHeaders.Authorization, "Bearer $botToken") }
            val result = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: throw SlackException(response.status.value, "invalid_response")
            if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) throw SlackException(response.status.value, result["error"]?.jsonPrimitive?.contentOrNull ?: "unknown_error")
            result
        }
        val uploadUrl = ticket["upload_url"]?.jsonPrimitive?.contentOrNull ?: throw IOException("upload_url 없음")
        val fileId = ticket["file_id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("file_id 없음")
        guarded {
            val response = http.post(uploadUrl) { setBody(bytes) }
            if (!response.status.isSuccess()) throw SlackException(response.status.value, "upload_failed")
        }
        call("files.completeUploadExternal", buildJsonObject {
            put("channel_id", channel)
            put("files", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("id", fileId); title?.let { put("title", it.take(250)) } })
            })
        }, botToken)
    }

    override suspend fun download(url: String, maxBytes: Long): ByteArray {
        val target = runCatching { Url(url) }.getOrNull()
        val base = Url(baseUrl)
        // 봇 토큰을 실어 보내므로 Slack 파일 서버(또는 시험용 가짜 서버)만 연다
        val allowed = target != null && (
            (target.protocol.name == "https" && target.host == "files.slack.com") || (target.host == base.host && target.port == base.port)
        )
        if (!allowed) throw IOException("Slack 파일 주소가 아닙니다")
        return guarded {
            val response = http.get(url) { header(HttpHeaders.Authorization, "Bearer $botToken") }
            if (!response.status.isSuccess()) throw IOException("파일 받기 실패: ${response.status.value}")
            // 권한(files:read)이 없으면 Slack은 로그인 HTML을 200으로 준다
            if (response.headers[HttpHeaders.ContentType]?.startsWith("text/html") == true) throw IOException("파일을 받을 권한이 없습니다 (files:read)")
            response.bodyAsBytes().also { if (it.size > maxBytes) throw IOException("파일이 너무 큽니다") }
        }
    }

    private suspend fun call(method: String, body: JsonObject?, token: String): JsonObject = guarded { send(method, body, token) }

    private suspend fun send(method: String, body: JsonObject?, token: String): JsonObject {
        var attempt = 0
        while (true) {
            val response = http.post("$baseUrl/$method") {
                header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) {
                    contentType(ContentType.Application.Json.withParameter("charset", "utf-8"))
                    setBody(body.toString())
                }
            }
            if (response.status.value == 429) {
                val wait = (response.headers["Retry-After"]?.toLongOrNull() ?: 1L) * 1000
                if (attempt++ < 2 && wait <= 10_000) {
                    log.debug { "Slack rate limit: ${wait}ms 뒤 다시 보냄 ($method)" }
                    delay(wait)
                    continue
                }
                throw SlackException(429, "ratelimited", wait)
            }
            val text = response.bodyAsText()
            val result = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw SlackException(response.status.value, "invalid_response")
            if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                throw SlackException(response.status.value, result["error"]?.jsonPrimitive?.contentOrNull ?: "unknown_error")
            }
            return result
        }
    }

    /** 네트워크 오류 메시지에 토큰이 들어가지 않게 지운다 */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SlackException) {
        throw e
    } catch (e: Exception) {
        throw IOException("${e::class.simpleName}: ${e.message}".replace(botToken, "<token>").replace(appToken, "<token>"))
    }

    companion object {
        /** 파일 받기에 봇 토큰을 실어 보내므로 다른 주소로 넘겨주는 응답은 따라가지 않는다 */
        // REST 호출은 JDK HttpClient 엔진: Ktor CIO는 연결을 다시 쓰지 못해 호출마다 약 1.2초(새 TLS 연결),
        // JDK 엔진은 연결을 이어 써 약 0.27초 (M15 실기기 확인, 텔레그램 API). JDK 엔진은 읽기 시간 제한이 없어 요청 전체 시간만 제한한다
        fun restClient() = HttpClient(Java) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
            }
        }

        /** Socket Mode용. 연결이 오래 이어지므로 연결 시간만 제한한다 */
        fun socketClient() = HttpClient(CIO) {
            install(WebSockets)
            install(HttpTimeout) { connectTimeoutMillis = 15_000 }
        }

        private val BOT_TOKEN = Regex("^xoxb-[A-Za-z0-9-]{20,}$")
        private val APP_TOKEN = Regex("^xapp-[A-Za-z0-9-]{20,}$")

        fun looksLikeBotToken(token: String) = BOT_TOKEN.matches(token)
        fun looksLikeAppToken(token: String) = APP_TOKEN.matches(token)
    }
}
