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

data class ChatMessage(val role: ChatRole, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** 추론 모델의 생각 양: low / medium / high. 지원하지 않는 모델·엔진은 무시한다. */
    val reasoningEffort: String? = null,
)

sealed interface ChatChunk {
    /** 생성된 텍스트 조각. */
    data class Delta(val text: String) : ChatChunk

    /** 추론 모델(gpt-oss 등)이 답변 전에 내는 생각 과정. 사용자 답변에는 넣지 않는다. */
    data class Reasoning(val text: String) : ChatChunk

    /** 스트림 끝. 엔진이 사용량을 주면 함께 담는다. */
    data class Done(val finishReason: String?, val usage: Usage?) : ChatChunk
}

data class Usage(val promptTokens: Int, val completionTokens: Int)
