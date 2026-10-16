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
}
