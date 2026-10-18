package dev.vesta.inference

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
internal data class WireMessage(
    val role: String,
    val content: String,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class WireToolCall(
    val id: String? = null,
    val index: Int? = null,
    val type: String? = "function",
    val function: WireFunctionCall? = null,
)

@Serializable
internal data class WireFunctionCall(val name: String? = null, val arguments: String? = null)

@Serializable
internal data class WireTool(val type: String = "function", val function: WireFunction)

@Serializable
internal data class WireFunction(val name: String, val description: String, val parameters: kotlinx.serialization.json.JsonObject)

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
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** Ollama 확장 필드. "forever"는 -1(무기한)로 보낸다. */
    @SerialName("keep_alive") val keepAlive: JsonElement? = null,
    val tools: List<WireTool>? = null,
)

internal fun keepAliveJson(value: String?): JsonElement? = when (value) {
    null -> null
    "forever" -> JsonPrimitive(-1)
    else -> JsonPrimitive(value)
}

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
internal data class ChunkDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    /** Ollama 형식 */
    val reasoning: String? = null,
    /** vLLM·mlx 계열 형식 */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

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
internal data class OllamaTagsResponse(val models: List<OllamaLocalModel> = emptyList())

@Serializable
internal data class OllamaLocalModel(val name: String, val size: Long = 0)

/** 모델 내리기: keep_alive 0으로 빈 요청을 보낸다. */
@Serializable
internal data class OllamaUnloadRequest(val model: String, @SerialName("keep_alive") val keepAlive: Int = 0)

@Serializable
internal data class OllamaPsResponse(val models: List<OllamaRunningModel> = emptyList())

@Serializable
internal data class OllamaRunningModel(
    val name: String,
    val size: Long = 0,
    @SerialName("size_vram") val sizeVram: Long = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
)
