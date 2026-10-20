package dev.vesta.channels.telegram

import dev.vesta.channels.ChannelApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Telegram Bot API 중 Vesta가 쓰는 부분 (PROJECT.md 6.4: long polling, 공개 주소 불필요) */
interface TelegramApi {
    suspend fun getMe(): TgUser
    suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate>
    suspend fun sendMessage(chatId: Long, text: String, html: Boolean = false, keyboard: TgKeyboard? = null, forceReply: Boolean = false): TgMessage
    /** [keyboard]가 null이면 버튼을 없앤다 */
    suspend fun editMessage(chatId: Long, messageId: Long, text: String, html: Boolean = false, keyboard: TgKeyboard? = null)
    suspend fun answerCallback(callbackId: String, text: String? = null)
    suspend fun sendTyping(chatId: Long)
    suspend fun setCommands(commands: List<Pair<String, String>>)
    /** [maxBytes]를 넘으면 받지 않는다 */
    suspend fun download(fileId: String, maxBytes: Long): ByteArray
}

/** Bot API 오류. 429면 [retryAfterSeconds]만큼 기다려야 한다 */
class TelegramException(val code: Int, val description: String, val retryAfterSeconds: Int? = null) :
    ChannelApiException("Telegram $code: $description", retryAfterSeconds?.times(1000L)) {
    override val notModified get() = code == 400 && "not modified" in description
    val badEntities get() = code == 400 && "parse entities" in description
}

@Serializable
data class TgUser(val id: Long, @SerialName("is_bot") val isBot: Boolean = false, val username: String? = null, @SerialName("first_name") val firstName: String? = null)

@Serializable
data class TgChat(val id: Long, val type: String)

@Serializable
data class TgDocument(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val from: TgUser? = null,
    /** 보낸 시각 (유닉스 초) */
    val date: Long = 0,
    val text: String? = null,
    val caption: String? = null,
    val document: TgDocument? = null,
)

@Serializable
data class TgCallbackQuery(val id: String, val from: TgUser, val message: TgMessage? = null, val data: String? = null)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)

@Serializable
data class TgButton(val text: String, @SerialName("callback_data") val callbackData: String)

@Serializable
data class TgKeyboard(@SerialName("inline_keyboard") val rows: List<List<TgButton>>)

@Serializable
private data class TgFile(@SerialName("file_path") val filePath: String? = null, @SerialName("file_size") val fileSize: Long? = null)

@Serializable
private data class TgResponse(
    val ok: Boolean,
    val result: JsonElement? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val parameters: TgResponseParameters? = null,
)

@Serializable
private data class TgResponseParameters(@SerialName("retry_after") val retryAfter: Int? = null)

/**
 * Ktor로 Bot API를 직접 부른다. 토큰은 URL에 들어가므로 오류 메시지에서 지운다 (로그에 남지 않게).
 */
class HttpTelegramApi(
    private val token: String,
    private val http: HttpClient = defaultClient(),
    private val baseUrl: String = "https://api.telegram.org",
) : TelegramApi {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun getMe(): TgUser = call("getMe", JsonObject(emptyMap()), TgUser.serializer())

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> = call(
        "getUpdates",
        buildJsonObject {
            offset?.let { put("offset", it) }
            put("timeout", timeoutSeconds)
            putJsonArray("allowed_updates") { add("message"); add("callback_query") }
        },
        ListSerializer(TgUpdate.serializer()),
    )

    override suspend fun sendMessage(chatId: Long, text: String, html: Boolean, keyboard: TgKeyboard?, forceReply: Boolean): TgMessage = call(
        "sendMessage",
        buildJsonObject {
            put("chat_id", chatId)
            put("text", text)
            if (html) put("parse_mode", "HTML")
            put("link_preview_options", buildJsonObject { put("is_disabled", true) })
            when {
                keyboard != null -> put("reply_markup", json.encodeToJsonElement(keyboard))
                forceReply -> put("reply_markup", buildJsonObject { put("force_reply", true) })
            }
        },
        TgMessage.serializer(),
    )

    override suspend fun editMessage(chatId: Long, messageId: Long, text: String, html: Boolean, keyboard: TgKeyboard?) {
        call<Unit>(
            "editMessageText",
            buildJsonObject {
                put("chat_id", chatId)
                put("message_id", messageId)
                put("text", text)
                if (html) put("parse_mode", "HTML")
                put("link_preview_options", buildJsonObject { put("is_disabled", true) })
                keyboard?.let { put("reply_markup", json.encodeToJsonElement(it)) }
            },
            null,
        )
    }

    override suspend fun answerCallback(callbackId: String, text: String?) {
        call<Unit>("answerCallbackQuery", buildJsonObject { put("callback_query_id", callbackId); text?.let { put("text", it) } }, null)
    }

    override suspend fun sendTyping(chatId: Long) {
        call<Unit>("sendChatAction", buildJsonObject { put("chat_id", chatId); put("action", "typing") }, null)
    }

    override suspend fun setCommands(commands: List<Pair<String, String>>) {
        call<Unit>(
            "setMyCommands",
            buildJsonObject {
                put("commands", kotlinx.serialization.json.buildJsonArray {
                    commands.forEach { (name, description) -> add(buildJsonObject { put("command", name); put("description", description) }) }
                })
            },
            null,
        )
    }

    override suspend fun download(fileId: String, maxBytes: Long): ByteArray {
        val file = call("getFile", buildJsonObject { put("file_id", fileId) }, TgFile.serializer())
        if ((file.fileSize ?: 0) > maxBytes) throw IOException("파일이 너무 큽니다")
        val path = file.filePath ?: throw IOException("파일 경로 없음")
        return guarded {
            val response = http.get("$baseUrl/file/bot$token/$path")
            if (!response.status.isSuccess()) throw IOException("파일 받기 실패: ${response.status.value}")
            response.bodyAsBytes().also { if (it.size > maxBytes) throw IOException("파일이 너무 큽니다") }
        }
    }

    private suspend fun <T> call(method: String, body: JsonObject, serializer: KSerializer<T>?): T = guarded {
        val text = http.post("$baseUrl/bot$token/$method") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyAsText()
        val response = json.decodeFromString(TgResponse.serializer(), text)
        if (!response.ok) throw TelegramException(response.errorCode ?: 0, response.description.orEmpty(), response.parameters?.retryAfter)
        @Suppress("UNCHECKED_CAST")
        if (serializer == null) Unit as T else json.decodeFromJsonElement(serializer, response.result ?: throw IOException("$method: 결과 없음"))
    }

    /** 네트워크 오류 메시지에 URL(토큰 포함)이 들어갈 수 있어 지운다 */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TelegramException) {
        throw e
    } catch (e: Exception) {
        throw IOException(redact("${e::class.simpleName}: ${e.message}"))
    }

    private fun redact(message: String) = message.replace(token, "<token>")

    companion object {
        /** long polling(최대 50초)보다 넉넉한 시간 제한 */
        fun defaultClient() = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 90_000
            }
        }

        private val TOKEN = Regex("^\\d{5,}:[A-Za-z0-9_-]{30,}$")

        /** BotFather가 주는 형식 (숫자:영숫자) */
        fun looksLikeToken(token: String) = TOKEN.matches(token)
    }
}
