package dev.vesta.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val InferenceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// --- OpenAI 호환 API 요청/응답 (필요한 필드만) ---

@Serializable
internal data class ModelsResponse(val data: List<WireModel> = emptyList())

@Serializable
internal data class WireModel(val id: String, @SerialName("owned_by") val ownedBy: String? = null)

@Serializable
internal data class WireMessage(val role: String, val content: String)

@Serializable
internal data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean)

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
internal data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: WireUsage? = null,
)

@Serializable
internal data class ChunkChoice(
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(val content: String? = null)

@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
internal data class EmbeddingsRequest(val model: String, val input: List<String>)

@Serializable
internal data class EmbeddingsResponse(val data: List<EmbeddingData> = emptyList())

@Serializable
internal data class EmbeddingData(val index: Int = 0, val embedding: List<Float>)

// --- Ollama 전용 API ---

@Serializable
internal data class OllamaVersion(val version: String)

@Serializable
internal data class OllamaPsResponse(val models: List<OllamaRunningModel> = emptyList())

@Serializable
internal data class OllamaRunningModel(
    val name: String,
    val size: Long = 0,
    @SerialName("size_vram") val sizeVram: Long = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
)
