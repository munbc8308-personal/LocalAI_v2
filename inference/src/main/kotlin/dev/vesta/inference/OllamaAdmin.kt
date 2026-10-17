package dev.vesta.inference

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ollama 전용 API. 버전 확인, 로드된 모델·메모리 조회(PROJECT.md 2.2 실측표), 모델 내리기에 쓴다. */
class OllamaAdmin(
    private val ollamaUrl: String,
    private val http: HttpClient,
) : ModelRuntime {
    suspend fun version(): String = http.get("$ollamaUrl/api/version").ensureOk().body<OllamaVersion>().version

    override suspend fun loadedModels(): List<LoadedModel> =
        http.get("$ollamaUrl/api/ps").ensureOk().body<OllamaPsResponse>().models
            .map { LoadedModel(it.name, it.size, it.sizeVram) }

    /** 디스크에 받아 둔 모델과 크기. 로드 전 메모리 예측에 쓴다. */
    override suspend fun localModelSizes(): Map<String, Long> =
        http.get("$ollamaUrl/api/tags").ensureOk().body<OllamaTagsResponse>().models.associate { it.name to it.size }

    override suspend fun unload(model: String) {
        http.post("$ollamaUrl/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(OllamaUnloadRequest(model))
        }.ensureOk()
    }
}

data class LoadedModel(val name: String, val sizeBytes: Long, val gpuBytes: Long)

/** 모델 로드·언로드를 다루는 엔진 쪽 기능. 테스트에서는 가짜 구현을 쓴다. */
interface ModelRuntime {
    suspend fun loadedModels(): List<LoadedModel>
    suspend fun localModelSizes(): Map<String, Long>
    suspend fun unload(model: String)
}
