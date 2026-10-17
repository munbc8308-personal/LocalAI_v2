package dev.vesta.retrieval

import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.knowledge.Reranker
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/v1/rerank` 형식(llama.cpp `llama-server --rerank`, Jina·Cohere 호환) 리랭커 클라이언트.
 * Ollama에는 리랭크 API가 없어 llama-server를 따로 띄운다 (PROJECT.md 13.3).
 */
class HttpReranker(
    private val baseUrl: String,
    private val model: String,
    private val http: HttpClient,
) : Reranker {
    override suspend fun rerank(question: String, candidates: List<Evidence>, topN: Int): List<Evidence> {
        if (candidates.isEmpty()) return candidates
        val response = http.post("$baseUrl/v1/rerank") {
            contentType(ContentType.Application.Json)
            setBody(RerankRequest(model, question, candidates.map { listOfNotNull(it.title, it.location).joinToString(" · ") + "\n" + it.text }, topN))
        }
        check(response.status.isSuccess()) { "rerank HTTP ${response.status.value}: ${response.bodyAsText().take(300)}" }
        return response.body<RerankResponse>().results
            .sortedByDescending { it.relevanceScore }
            .take(topN)
            .map { candidates[it.index].copy(score = it.relevanceScore) }
    }
}

@Serializable
internal data class RerankRequest(
    val model: String,
    val query: String,
    val documents: List<String>,
    @SerialName("top_n") val topN: Int,
)

@Serializable
internal data class RerankResponse(val results: List<RerankResult> = emptyList())

@Serializable
internal data class RerankResult(val index: Int, @SerialName("relevance_score") val relevanceScore: Double)
