package dev.vesta.channels.discord

import dev.vesta.channels.ChannelApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

private val log = KotlinLogging.logger {}

/** Discord REST API 중 Vesta가 쓰는 부분 (PROJECT.md 6.4). 받기는 [DiscordGateway]가 한다 */
interface DiscordApi {
    suspend fun me(): DcUser
    /** 초대 주소에 쓰는 애플리케이션 ID */
    suspend fun applicationId(): String
    suspend fun gatewayUrl(): String
    /** [rows]는 버튼 줄. 비어 있으면 버튼 없음 */
    suspend fun createMessage(channelId: String, content: String, rows: List<List<DcButton>> = emptyList()): DcMessage
    /** 내용을 바꾸고 버튼을 [rows]로 바꾼다 (비우면 버튼을 없앤다) */
    suspend fun editMessage(channelId: String, messageId: String, content: String, rows: List<List<DcButton>> = emptyList())
    /** 버튼·입력 창에 대한 응답 (3초 안에 한 번) */
    suspend fun respond(interactionId: String, interactionToken: String, response: JsonObject)
    /** 첨부 파일. Discord CDN 주소만 받는다. [maxBytes]를 넘으면 받지 않는다 */
    suspend fun download(url: String, maxBytes: Long): ByteArray
}

class DiscordException(val code: Int, val description: String, retryAfterMillis: Long? = null) :
    ChannelApiException("Discord $code: $description", retryAfterMillis)

@Serializable
data class DcUser(val id: String, val username: String? = null, val bot: Boolean = false)

@Serializable
data class DcAttachment(val id: String, val filename: String, val size: Long? = null, val url: String)

@Serializable
data class DcMessage(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    /** 서버(길드) 대화방이면 있다. DM이면 null */
    @SerialName("guild_id") val guildId: String? = null,
    val author: DcUser? = null,
    val content: String = "",
    /** ISO 8601 */
    val timestamp: String? = null,
    val attachments: List<DcAttachment> = emptyList(),
)

/** [style]: 1 파랑(primary), 2 회색, 4 빨강 */
data class DcButton(val label: String, val customId: String, val style: Int = 2)

@Serializable
data class DcMember(val user: DcUser? = null)

@Serializable
data class DcInteraction(
    val id: String,
    val token: String,
    /** 3 버튼, 5 입력 창 제출 */
    val type: Int,
    @SerialName("channel_id") val channelId: String? = null,
    /** DM이면 user, 서버면 member.user */
    val user: DcUser? = null,
    val member: DcMember? = null,
    val data: DcInteractionData? = null,
)

@Serializable
data class DcInteractionData(
    @SerialName("custom_id") val customId: String? = null,
    val components: List<DcModalRow> = emptyList(),
)

/** 입력 창 제출 값. 줄(type 1) 안의 components, 또는 라벨(type 18) 안의 component */
@Serializable
data class DcModalRow(val components: List<DcModalInput> = emptyList(), val component: DcModalInput? = null)

@Serializable
data class DcModalInput(@SerialName("custom_id") val customId: String? = null, val value: String? = null)

/**
 * Ktor로 REST API를 직접 부른다. 429면 Discord가 알려 준 시간만큼 기다렸다 다시 보낸다(10초 이하일 때 두 번까지).
 * 모든 메시지는 멘션을 끄고(allowed_mentions) 링크 미리보기를 끈다(SUPPRESS_EMBEDS).
 */
class HttpDiscordApi(
    private val token: String,
    private val http: HttpClient = restClient(),
    private val baseUrl: String = "https://discord.com/api/v10",
) : DiscordApi {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun me(): DcUser = json.decodeFromString(DcUser.serializer(), call(HttpMethod.Get, "/users/@me"))

    override suspend fun applicationId(): String =
        json.parseToJsonElement(call(HttpMethod.Get, "/oauth2/applications/@me")).jsonObject["id"]?.jsonPrimitive?.content
            ?: throw IOException("애플리케이션 ID 없음")

    override suspend fun gatewayUrl(): String =
        json.parseToJsonElement(call(HttpMethod.Get, "/gateway/bot")).jsonObject["url"]?.jsonPrimitive?.content
            ?: throw IOException("Gateway 주소 없음")

    override suspend fun createMessage(channelId: String, content: String, rows: List<List<DcButton>>): DcMessage =
        json.decodeFromString(DcMessage.serializer(), call(HttpMethod.Post, "/channels/$channelId/messages", messageBody(content, rows)))

    override suspend fun editMessage(channelId: String, messageId: String, content: String, rows: List<List<DcButton>>) {
        call(HttpMethod.Patch, "/channels/$channelId/messages/$messageId", messageBody(content, rows))
    }

    override suspend fun respond(interactionId: String, interactionToken: String, response: JsonObject) {
        call(HttpMethod.Post, "/interactions/$interactionId/$interactionToken/callback", response, auth = false)
    }

    override suspend fun download(url: String, maxBytes: Long): ByteArray {
        val target = runCatching { Url(url) }.getOrNull()
        val base = Url(baseUrl)
        // 받은 메시지에 적힌 주소라도 Discord CDN(또는 시험용 가짜 서버)만 연다
        val allowed = target != null && (
            (target.protocol.name == "https" && target.host in CDN_HOSTS) || (target.host == base.host && target.port == base.port)
        )
        if (!allowed) throw IOException("Discord 첨부 주소가 아닙니다")
        return guarded {
            val response = http.get(url)
            if (!response.status.isSuccess()) throw IOException("파일 받기 실패: ${response.status.value}")
            response.bodyAsBytes().also { if (it.size > maxBytes) throw IOException("파일이 너무 큽니다") }
        }
    }

    private fun messageBody(content: String, rows: List<List<DcButton>>) = buildJsonObject {
        put("content", content)
        putJsonArray("components") { rows.forEach { add(row(it)) } }
        putJsonObject("allowed_mentions") { putJsonArray("parse") {} }
        put("flags", SUPPRESS_EMBEDS)
    }

    private fun row(buttons: List<DcButton>) = buildJsonObject {
        put("type", 1)
        put("components", buildJsonArray {
            buttons.forEach { b ->
                add(buildJsonObject {
                    put("type", 2)
                    put("style", b.style)
                    put("label", b.label.take(80))
                    put("custom_id", b.customId)
                })
            }
        })
    }

    private suspend fun call(method: HttpMethod, path: String, body: JsonObject? = null, auth: Boolean = true): String =
        guarded { send(method, path, body, auth) }

    private suspend fun send(method: HttpMethod, path: String, body: JsonObject?, auth: Boolean): String {
        var attempt = 0
        while (true) {
            val response = http.request("$baseUrl$path") {
                this.method = method
                if (auth) header(HttpHeaders.Authorization, "Bot $token")
                header(HttpHeaders.UserAgent, USER_AGENT)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
            val text = response.bodyAsText()
            if (response.status.isSuccess()) return text
            val error = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val message = error?.get("message")?.jsonPrimitive?.content ?: response.status.description
            if (response.status.value == 429) {
                val seconds = error?.get("retry_after")?.jsonPrimitive?.doubleOrNull ?: response.headers["Retry-After"]?.toDoubleOrNull() ?: 1.0
                val wait = ceil(seconds * 1000).toLong().coerceAtLeast(100)
                if (attempt++ < 2 && wait <= 10_000) {
                    log.debug { "Discord rate limit: ${wait}ms 뒤 다시 보냄 ($path)" }
                    delay(wait)
                    continue
                }
                throw DiscordException(429, message, wait)
            }
            throw DiscordException(response.status.value, message)
        }
    }

    /** 네트워크 오류 메시지에 토큰이 들어가지 않게 지운다 */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DiscordException) {
        throw e
    } catch (e: Exception) {
        throw IOException("${e::class.simpleName}: ${e.message}".replace(token, "<token>"))
    }

    companion object {
        private const val SUPPRESS_EMBEDS = 1 shl 2
        private val CDN_HOSTS = setOf("cdn.discordapp.com", "media.discordapp.net")
        private const val USER_AGENT = "DiscordBot (https://github.com/munbc8308-personal/LocalAI_v2, 1.0)"

        fun restClient() = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }

        /** Gateway용. 연결이 오래 이어지므로 연결 시간만 제한한다 */
        fun gatewayClient() = HttpClient(CIO) {
            install(WebSockets)
            install(HttpTimeout) { connectTimeoutMillis = 15_000 }
        }

        private val TOKEN = Regex("^[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{20,}$")

        /** Developer Portal의 Bot 토큰 형식 (점으로 나뉜 세 부분) */
        fun looksLikeToken(token: String) = TOKEN.matches(token)

        /** 봇을 내 서버에 넣는 주소 (권한 없음: DM만 쓴다) */
        fun inviteUrl(applicationId: String) = "https://discord.com/oauth2/authorize?client_id=$applicationId&scope=bot&permissions=0"
    }
}
