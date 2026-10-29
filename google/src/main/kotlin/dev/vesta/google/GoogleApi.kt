package dev.vesta.google

import dev.vesta.core.UserId
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/** Google이 연결돼 있지 않거나 갱신이 안 될 때 */
class GoogleNotConnected : Exception("Google 계정이 연결되지 않았거나 연결이 끊겼습니다. 웹 설정의 Google에서 다시 연결해 주세요")

/** Google API 오류 (상태와 Google이 준 이유) */
class GoogleApiException(val status: Int, message: String) : Exception(message)

/** 사용자 토큰을 붙여 Google API를 부른다. 401이면 토큰을 새로 받아 한 번 더 */
class GoogleApi(private val http: HttpClient, private val auth: GoogleAuth) {
    fun connected(user: UserId) = auth.email(user) != null

    suspend fun call(user: UserId, method: HttpMethod, url: Url, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        var response = send(user, method, url, block)
        if (response.status == HttpStatusCode.Unauthorized) {
            auth.invalidate(user)
            response = send(user, method, url, block)
        }
        if (!response.status.isSuccess()) {
            val reason = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonObject?.str("message") }.getOrNull()
            throw GoogleApiException(response.status.value, reason ?: response.status.description)
        }
        return response
    }

    suspend fun json(user: UserId, method: HttpMethod, url: Url, block: HttpRequestBuilder.() -> Unit = {}): JsonObject =
        Json.parseToJsonElement(call(user, method, url, block).bodyAsText()).jsonObject

    /** 파일 받기. [limit]보다 크면 멈춘다 */
    suspend fun bytes(user: UserId, url: Url, limit: Int): ByteArray {
        val channel = call(user, HttpMethod.Get, url).bodyAsChannel()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = channel.readAvailable(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw GoogleApiException(413, "파일이 너무 큽니다 (${limit / 1024 / 1024}MB까지)")
        }
        return out.toByteArray()
    }

    private suspend fun send(user: UserId, method: HttpMethod, url: Url, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        val token = auth.accessToken(user) ?: throw GoogleNotConnected()
        return http.request(url) {
            this.method = method
            bearerAuth(token)
            block()
        }
    }
}

/** Google용 HTTP 클라이언트 (바깥 인터넷, 오류 응답도 직접 본다) */
fun googleHttpClient(): HttpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
    expectSuccess = false
    install(io.ktor.client.plugins.HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 120_000
        socketTimeoutMillis = 60_000
    }
}
