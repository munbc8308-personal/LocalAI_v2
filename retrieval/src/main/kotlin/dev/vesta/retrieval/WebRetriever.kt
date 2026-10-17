package dev.vesta.retrieval

import dev.vesta.core.SourceType
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.web.FetchResult
import dev.vesta.core.web.PageFetcher
import dev.vesta.core.web.SearchHit
import dev.vesta.core.web.WebPage
import dev.vesta.core.web.WebSearchClient
import dev.vesta.knowledge.Chunker
import dev.vesta.knowledge.EmbeddingProfile
import dev.vesta.knowledge.PlainTextParserFacade
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/** 웹 검색 진행 상황. 에이전트가 사용자에게 보여 준다 (W-06: 보낸 검색어 표시). */
sealed interface WebProgress {
    data class Searching(val query: String) : WebProgress
    data class Reading(val count: Int) : WebProgress
}

data class WebResult(
    val evidence: List<Evidence>,
    val queries: List<String>,
    val pagesRead: Int,
    val failures: List<FetchResult.Failed>,
)

/**
 * 웹 근거 검색 (PROJECT.md 5.1 [2] 웹, W-02·W-03).
 * 검색 → 상위 페이지 병렬 수집 → 본문 청킹 → 질문과 임베딩 유사도로 순위 → 페이지당 최대 [perPage]개.
 * 수집에 실패한 결과는 검색 요약(snippet)을 낮은 점수로 대신 쓴다.
 */
class WebRetriever(
    private val search: WebSearchClient,
    private val fetcher: PageFetcher,
    private val inference: InferenceClient,
    private val embeddingModel: String,
    private val maxPages: Int = 6,
    private val perPage: Int = 2,
    private val concurrency: Int = 4,
    private val chunker: Chunker = Chunker(targetChars = 1_000, overlapChars = 150),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun retrieve(
        question: String,
        queries: List<String>,
        urls: List<String> = emptyList(),
        limit: Int = 6,
        onProgress: suspend (WebProgress) -> Unit = {},
    ): WebResult {
        // 1. 검색 (URL을 직접 주면 검색하지 않는다)
        val hits = if (urls.isNotEmpty()) {
            urls.map { SearchHit(it, it, "", null, null) }
        } else {
            queries.flatMap { query ->
                onProgress(WebProgress.Searching(query))
                runCatching { search.search(query, maxPages) }.getOrDefault(emptyList())
            }.distinctBy { normalize(it.url) }.take(maxPages)
        }
        if (hits.isEmpty()) return WebResult(emptyList(), queries, 0, emptyList())

        // 2. 수집 (병렬, 동시 [concurrency]개)
        onProgress(WebProgress.Reading(hits.size))
        val gate = Semaphore(concurrency)
        val fetched = coroutineScope {
            hits.map { hit -> async { gate.withPermit { hit to fetcher.fetch(hit.url) } } }.awaitAll()
        }
        val pages = fetched.mapNotNull { (hit, result) -> (result as? FetchResult.Ok)?.let { hit to it.page } }
        val failures = fetched.mapNotNull { it.second as? FetchResult.Failed }

        // 3. 청킹 + 임베딩 유사도 순위
        val candidates = pages.flatMap { (hit, page) ->
            chunker.chunk(PlainTextParserFacade.parse(page.text, page.title)).map { Triple(hit, page, it.text) }
        }
        val profile = EmbeddingProfile.of(embeddingModel)
        val scored = if (candidates.isEmpty()) emptyList() else {
            val queryVector = inference.embed(embeddingModel, listOf(profile.query(question))).first()
            val vectors = candidates.chunked(32).flatMap { batch -> inference.embed(embeddingModel, batch.map { "${it.second.title}\n${it.third}" }) }
            candidates.zip(vectors) { candidate, vector -> candidate to cosine(queryVector, vector) }
        }
        val fromPages = scored
            .groupBy { it.first.second.url }
            .flatMap { (_, chunks) -> chunks.sortedByDescending { it.second }.take(perPage) }
            .sortedByDescending { it.second }
            .map { (candidate, score) -> evidence(candidate.second, candidate.third, score, candidate.first) }

        // 4. 수집 실패한 결과는 검색 요약으로 대신 (점수 낮게)
        val fromSnippets = fetched
            .filter { it.second is FetchResult.Failed && it.first.snippet.length > 40 }
            .map { (hit, _) -> Evidence(SourceType.WEB, hit.url, hit.title, "${host(hit.url)} · 검색 요약", hit.snippet, 0.0) }

        return WebResult((fromPages + fromSnippets).take(limit), queries, pages.size, failures)
    }

    private fun evidence(page: WebPage, text: String, score: Double, hit: SearchHit) = Evidence(
        source = SourceType.WEB,
        ref = page.url,
        title = page.title.ifBlank { hit.title },
        location = listOfNotNull(
            host(page.url),
            (page.publishedDate ?: hit.publishedDate)?.take(10)?.let { "게시 $it" },
            "${DATE.withZone(zone).format(page.fetchedAt)} 접속",
        ).joinToString(" · "),
        text = text,
        score = score,
    )

    private fun host(url: String) = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url)
    private fun normalize(url: String) = url.substringBefore('#').trimEnd('/')

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
