package dev.vesta.web

import dev.vesta.core.web.SearchHit
import dev.vesta.core.web.WebSearchClient
import dev.vesta.storage.WebCacheRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 로컬 SearXNG JSON API (PROJECT.md W-01). 보낸 검색어는 기록한다 (5.4, W-06). */
class SearxngClient(
    private val baseUrl: String,
    private val http: HttpClient,
    private val log: WebCacheRepository? = null,
) : WebSearchClient {
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val response = http.get("$baseUrl/search") {
            parameter("q", query)
            parameter("format", "json")
        }
        check(response.status.isSuccess()) { "SearXNG HTTP ${response.status.value}: ${response.bodyAsText().take(200)}" }
        val hits = response.body<SearxngResponse>().results
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url.trimEnd('/') }
            .take(limit)
            .map { SearchHit(it.title.orEmpty(), it.url, it.content.orEmpty(), it.engine, it.publishedDate) }
        log?.logSearch(query, "searxng", hits.size)
        return hits
    }
}

@Serializable
internal data class SearxngResponse(val results: List<SearxngResult> = emptyList())

@Serializable
internal data class SearxngResult(
    val url: String,
    val title: String? = null,
    val content: String? = null,
    val engine: String? = null,
    @SerialName("publishedDate") val publishedDate: String? = null,
)
