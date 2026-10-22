package dev.vesta.inference

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line

/** Ollama 전용 API. 버전 확인, 로드된 모델·메모리 조회(PROJECT.md 2.2 실측표), 모델 내리기, 모델 받기·지우기(M16)에 쓴다. */
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

    /** 받아 둔 모델 (이름·크기·digest) */
    suspend fun localModels(): List<LocalModel> =
        http.get("$ollamaUrl/api/tags").ensureOk().body<OllamaTagsResponse>().models.map { LocalModel(it.name, it.size, it.digest) }

    /**
     * 모델 받기 (`ollama pull`). 수십 GB라 한 시간 넘게 걸릴 수 있어 요청 시간 제한을 두지 않는다.
     * [onProgress]에 받은 바이트·전체 바이트를 넘긴다. 실패하면 예외
     */
    suspend fun pull(model: String, onProgress: suspend (completed: Long, total: Long) -> Unit = { _, _ -> }) {
        http.preparePost("$ollamaUrl/api/pull") {
            contentType(ContentType.Application.Json)
            setBody(OllamaPullRequest(model))
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = 600_000 }
        }.execute { response ->
            response.ensureOk()
            val channel = response.bodyAsChannel()
            var last = ""
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val progress = InferenceJson.decodeFromString(OllamaPullProgress.serializer(), line)
                progress.error?.let { error("모델 받기 실패 ($model): $it") }
                last = progress.status
                if (progress.total > 0) onProgress(progress.completed, progress.total)
            }
            check(last == "success") { "모델 받기가 끝나지 않았습니다 ($model): $last" }
        }
    }

    /** 받아 둔 모델 지우기 (`ollama rm`) */
    suspend fun delete(model: String) {
        http.delete("$ollamaUrl/api/delete") {
            contentType(ContentType.Application.Json)
            setBody(OllamaDeleteRequest(model))
        }.ensureOk()
    }

    override suspend fun unload(model: String) {
        http.post("$ollamaUrl/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(OllamaUnloadRequest(model))
        }.ensureOk()
    }
}

data class LoadedModel(val name: String, val sizeBytes: Long, val gpuBytes: Long)

data class LocalModel(val name: String, val sizeBytes: Long, val digest: String)

/** 모델 로드·언로드를 다루는 엔진 쪽 기능. 테스트에서는 가짜 구현을 쓴다. */
interface ModelRuntime {
    suspend fun loadedModels(): List<LoadedModel>
    suspend fun localModelSizes(): Map<String, Long>
    suspend fun unload(model: String)
}
