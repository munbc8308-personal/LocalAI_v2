package dev.vesta.core.inference

import kotlinx.coroutines.flow.Flow

/** 추론 엔진 클라이언트. OpenAI 호환 API 기준이라 엔진을 바꿔도 URL만 바꾸면 된다 (PROJECT.md 3.3). */
interface InferenceClient {
    suspend fun listModels(): List<ModelInfo>
    fun chat(request: ChatRequest): Flow<ChatChunk>
    suspend fun embed(model: String, texts: List<String>): List<FloatArray>
}

data class ModelInfo(val id: String, val ownedBy: String? = null)

enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /** assistant가 요청한 도구 호출 (T-02) */
    val toolCalls: List<dev.vesta.core.tools.ToolCall> = emptyList(),
    /** role TOOL일 때 어느 호출의 결과인지 */
    val toolCallId: String? = null,
    /** 사용자 메시지에 붙인 이미지 (비전 모델용, V-04) */
    val images: List<dev.vesta.core.media.ImageInput> = emptyList(),
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** 추론 모델의 생각 양: none / low / medium / high. none은 생각을 끈다(Qwen3.5). 지원하지 않는 모델·엔진은 무시한다. */
    val reasoningEffort: String? = null,
    /** 모델을 메모리에 유지할 시간 (Ollama keep_alive). 예: "30m", "forever". null이면 엔진 기본값. */
    val keepAlive: String? = null,
    /** 모델이 호출할 수 있는 도구 (T-01). 비어 있으면 도구 없이 생성. */
    val tools: List<dev.vesta.core.tools.ToolSpec> = emptyList(),
)

sealed interface ChatChunk {
    /** 생성된 텍스트 조각. */
    data class Delta(val text: String) : ChatChunk

    /** 추론 모델(gpt-oss 등)이 답변 전에 내는 생각 과정. 사용자 답변에는 넣지 않는다. */
    data class Reasoning(val text: String) : ChatChunk

    /** 모델이 도구 호출을 요청했다 (스트림이 끝날 때 한 번, 인자는 다 모은 상태). */
    data class ToolCalls(val calls: List<dev.vesta.core.tools.ToolCall>) : ChatChunk

    /** 스트림 끝. 엔진이 사용량을 주면 함께 담는다. */
    data class Done(val finishReason: String?, val usage: Usage?) : ChatChunk
}

data class Usage(val promptTokens: Int, val completionTokens: Int)
