package dev.vesta.webui

import dev.vesta.api.ApiJson
import dev.vesta.api.CSRF_HEADER
import dev.vesta.api.ChatRequestDto
import dev.vesta.api.EventDto
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.json

/** 서버가 2xx가 아닌 응답을 줬다. [message]는 서버의 `error` 필드 */
class HttpError(val status: Int, override val message: String) : Exception(message)

/**
 * 같은 출처의 Vesta API 호출 (PROJECT.md 6.3). 인증은 HttpOnly 세션 쿠키가 하고,
 * GET 외 요청에는 로그인 때 받은 CSRF 값을 `X-Vesta-CSRF` 헤더로 붙인다.
 */
object Http {
    var csrf: String? = null

    suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        ApiJson.decodeFromString(serializer, request("GET", path, null).ensureOk().text().await())

    suspend fun <T> post(path: String, body: String, serializer: KSerializer<T>): T =
        ApiJson.decodeFromString(serializer, request("POST", path, body).ensureOk().text().await())

    suspend fun post(path: String, body: String = "{}") {
        request("POST", path, body).ensureOk()
    }

    suspend fun delete(path: String) {
        request("DELETE", path, null).ensureOk()
    }

    /**
     * `/api/chat`의 SSE 응답을 이벤트로 읽는다 (POST라 EventSource를 쓸 수 없어 fetch 스트림을 직접 읽는다).
     * 수집을 취소하면 요청을 끊고, 서버는 생성을 멈춘다.
     */
    fun chat(request: ChatRequestDto): Flow<EventDto> = flow {
        val controller = js("new AbortController()")
        try {
            val response = request("POST", "/api/chat", ApiJson.encodeToString(ChatRequestDto.serializer(), request), controller.signal).ensureOk()
            val reader = response.body.getReader()
            val decoder = js("new TextDecoder('utf-8')")
            var buffer = ""
            while (true) {
                val chunk = (reader.read() as kotlin.js.Promise<dynamic>).await()
                if (chunk.done == true) break
                buffer += decoder.decode(chunk.value, json("stream" to true)) as String
                // 이벤트는 빈 줄로 구분된다
                while (true) {
                    val end = buffer.indexOf("\n\n")
                    if (end < 0) break
                    val block = buffer.substring(0, end)
                    buffer = buffer.substring(end + 2)
                    block.lineSequence().filter { it.startsWith("data:") }.forEach { line ->
                        emit(ApiJson.decodeFromString(EventDto.serializer(), line.removePrefix("data:").trim()))
                    }
                }
            }
        } finally {
            controller.abort()
        }
    }

    private suspend fun request(method: String, path: String, body: String?, signal: dynamic = undefined): Response {
        val headers = json("Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json"
        if (method != "GET") csrf?.let { headers[CSRF_HEADER] = it }
        val init = RequestInit(method = method, headers = headers, body = body ?: undefined, credentials = "same-origin".asDynamic())
        if (signal != undefined) init.asDynamic().signal = signal
        return window.fetch(path, init).await()
    }

    private suspend fun Response.ensureOk(): Response {
        if (ok) return this
        val text = runCatching { text().await() }.getOrDefault("")
        val message = runCatching { ApiJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        throw HttpError(status.toInt(), message ?: "서버 오류 $status")
    }
}
