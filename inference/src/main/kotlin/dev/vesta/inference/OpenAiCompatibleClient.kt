package dev.vesta.inference

import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.inference.ModelInfo
import dev.vesta.core.inference.Usage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * OpenAI 호환 API 클라이언트 (Ollama, mlx-lm server, vllm-mlx 공통).
 * [baseUrl]은 `/v1`까지 포함한다. 예: `http://127.0.0.1:11434/v1`
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val http: HttpClient,
) : InferenceClient {

    override suspend fun listModels(): List<ModelInfo> {
        val response: ModelsResponse = http.get("$baseUrl/models").ensureOk().body()
        return response.data.map { ModelInfo(it.id, it.ownedBy) }
    }

    override fun chat(request: ChatRequest): Flow<ChatChunk> = flow {
        val body = ChatCompletionRequest(
            model = request.model,
            messages = request.messages.map { WireMessage(it.role.name.lowercase(), it.content) },
            stream = true,
            streamOptions = StreamOptions(includeUsage = true),
            temperature = request.temperature,
            maxTokens = request.maxTokens,
        )
        http.preparePost("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            response.ensureOk()
            val channel = response.bodyAsChannel()
            var finishReason: String? = null
            var usage: Usage? = null
            while (true) {
                val line = channel.readUTF8Line() ?: break
                val event = SseParser.parse(line) ?: continue
                if (event == SseParser.DONE) break
                val chunk = InferenceJson.decodeFromString(ChatCompletionChunk.serializer(), event)
                chunk.usage?.let { usage = Usage(it.promptTokens, it.completionTokens) }
                for (choice in chunk.choices) {
                    val delta = choice.delta
                    (delta?.reasoning ?: delta?.reasoningContent)?.takeIf { it.isNotEmpty() }?.let { emit(ChatChunk.Reasoning(it)) }
                    delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(ChatChunk.Delta(it)) }
                    choice.finishReason?.let { finishReason = it }
                }
            }
            emit(ChatChunk.Done(finishReason, usage))
        }
    }

    override suspend fun embed(model: String, texts: List<String>): List<FloatArray> {
        val response: EmbeddingsResponse = http.post("$baseUrl/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(EmbeddingsRequest(model, texts))
        }.ensureOk().body()
        return response.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
    }
}

/** 엔진이 오류를 돌려주면 상태 코드와 본문을 담아 예외로 바꾼다. */
internal suspend fun io.ktor.client.statement.HttpResponse.ensureOk(): io.ktor.client.statement.HttpResponse {
    if (!status.isSuccess()) throw InferenceException("HTTP ${status.value}: ${bodyAsText().take(500)}")
    return this
}

class InferenceException(message: String) : RuntimeException(message)

/** SSE `data:` 줄만 꺼낸다. 빈 줄·주석·다른 필드는 null. */
internal object SseParser {
    const val DONE = "[DONE]"

    fun parse(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim().ifEmpty { null }
    }
}
