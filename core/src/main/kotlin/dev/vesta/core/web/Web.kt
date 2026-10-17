package dev.vesta.core.web

import java.time.Instant

/** 검색 엔진 결과 한 건 (PROJECT.md 8.1 WebSearchClient). */
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val engine: String?,
    val publishedDate: String?,
)

interface WebSearchClient {
    suspend fun search(query: String, limit: Int): List<SearchHit>
}

/** 수집·본문 추출한 웹 페이지. */
data class WebPage(
    val url: String,
    val title: String,
    val text: String,
    val fetchedAt: Instant,
    val publishedDate: String? = null,
)

sealed interface FetchResult {
    data class Ok(val page: WebPage, val fromCache: Boolean) : FetchResult
    data class Failed(val url: String, val reason: String) : FetchResult
}

interface PageFetcher {
    suspend fun fetch(url: String): FetchResult
}
