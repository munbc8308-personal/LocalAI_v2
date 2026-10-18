package dev.vesta.inference

import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.inference.ModelInfo
import dev.vesta.core.inference.Usage
import dev.vesta.core.tools.ToolCall
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
            messages = request.messages.map { message ->
                WireMessage(
                    role = message.role.name.lowercase(),
                    content = message.content,
                    toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }
                        ?.map { WireToolCall(id = it.id, type = "function", function = WireFunctionCall(it.name, it.arguments)) },
                    toolCallId = message.toolCallId,
                )
            },
            stream = true,
            streamOptions = StreamOptions(includeUsage = true),
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            reasoningEffort = request.reasoningEffort,
            keepAlive = keepAliveJson(request.keepAlive),
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { WireTool(function = WireFunction(it.name, it.description, it.parameters)) },
        )
        http.preparePost("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            response.ensureOk()
            val channel = response.bodyAsChannel()
            var finishReason: String? = null
            var usage: Usage? = null
            // OpenAI 형식은 도구 호출 인자를 여러 조각으로 나눠 보낼 수 있다 → index별로 모은다
            val pendingCalls = sortedMapOf<Int, PendingCall>()
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
                    delta?.toolCalls?.forEachIndexed { position, call ->
                        val pending = pendingCalls.getOrPut(call.index ?: position) { PendingCall() }
                        call.id?.let { pending.id = it }
                        call.function?.name?.let { pending.name += it }
                        call.function?.arguments?.let { pending.arguments.append(it) }
                    }
                    choice.finishReason?.let { finishReason = it }
                }
            }
            if (pendingCalls.isNotEmpty()) {
                emit(
                    ChatChunk.ToolCalls(
                        pendingCalls.entries.map { (index, call) ->
                            ToolCall(call.id ?: "call_$index", call.name, call.arguments.toString().ifBlank { "{}" })
                        },
                    ),
                )
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

private class PendingCall(var id: String? = null, var name: String = "", val arguments: StringBuilder = StringBuilder())

class InferenceException(message: String) : RuntimeException(message)

/** SSE `data:` 줄만 꺼낸다. 빈 줄·주석·다른 필드는 null. */
internal object SseParser {
    const val DONE = "[DONE]"

    fun parse(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim().ifEmpty { null }
    }
}
