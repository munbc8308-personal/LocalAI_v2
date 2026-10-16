package dev.vesta.inference

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/** Ollama 전용 API. 버전 확인과 로드된 모델의 메모리 측정(PROJECT.md 2.2 실측표)에 쓴다. */
class OllamaAdmin(
    private val ollamaUrl: String,
    private val http: HttpClient,
) {
    suspend fun version(): String = http.get("$ollamaUrl/api/version").ensureOk().body<OllamaVersion>().version

    suspend fun loadedModels(): List<LoadedModel> =
        http.get("$ollamaUrl/api/ps").ensureOk().body<OllamaPsResponse>().models
            .map { LoadedModel(it.name, it.size, it.sizeVram) }
}

data class LoadedModel(val name: String, val sizeBytes: Long, val gpuBytes: Long)
