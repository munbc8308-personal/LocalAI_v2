package dev.vesta.inference

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

object HttpClients {
    /** 로컬 엔진용 HTTP 클라이언트. 생성은 오래 걸릴 수 있어 요청 타임아웃을 길게 둔다. */
    fun local(requestTimeoutSeconds: Long, engine: HttpClientEngine = CIO.create()): HttpClient =
        HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(InferenceJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = requestTimeoutSeconds * 1_000
                socketTimeoutMillis = requestTimeoutSeconds * 1_000
            }
        }

    /**
     * 외부 웹 페이지 수집용. 요청마다 짧은 타임아웃을 둔다.
     * 리다이렉트는 **자동으로 따라가지 않는다** — 수집기가 한 번씩 내부 주소인지 검사하며 따라간다 (SSRF 방지).
     */
    fun web(timeoutSeconds: Long, engine: HttpClientEngine = CIO.create()): HttpClient =
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = timeoutSeconds * 1_000
                socketTimeoutMillis = timeoutSeconds * 1_000
            }
        }
}
