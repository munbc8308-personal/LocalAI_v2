package dev.vesta.retrieval

import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.inference.ModelInfo
import dev.vesta.core.web.FetchResult
import dev.vesta.core.web.PageFetcher
import dev.vesta.core.web.SearchHit
import dev.vesta.core.web.WebPage
import dev.vesta.core.web.WebSearchClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant

/** "고양이"가 들어간 글은 질문(고양이)과 가깝게, 나머지는 멀게 임베딩한다. */
private object KeywordEmbedder : InferenceClient {
    override suspend fun listModels() = emptyList<ModelInfo>()
    override fun chat(request: ChatRequest): Flow<ChatChunk> = emptyFlow()
    override suspend fun embed(model: String, texts: List<String>) =
        texts.map { if ("고양이" in it) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
}

class WebRetrieverTest : FunSpec({
    val hits = listOf(
        SearchHit("고양이 백과", "https://a.com/cat", "", null, "2026-10-01"),
        SearchHit("강아지 백과", "https://b.com/dog", "", null, null),
        SearchHit("막힌 사이트", "https://c.com/blocked", "고양이에 대한 검색 요약 문장입니다. 수집에 실패해도 이 요약은 근거로 쓸 수 있습니다.", null, null),
    )
    val searcher = object : WebSearchClient {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, limit: Int) = hits.also { queries += query }
    }
    val fetcher = object : PageFetcher {
        override suspend fun fetch(url: String): FetchResult = when {
            "cat" in url -> FetchResult.Ok(WebPage(url, "고양이 백과", (1..5).joinToString("\n\n") { "고양이 문단 $it " + "야옹".repeat(150) }, Instant.EPOCH), false)
            "dog" in url -> FetchResult.Ok(WebPage(url, "강아지 백과", "강아지 문단 " + "멍멍".repeat(100), Instant.EPOCH), false)
            else -> FetchResult.Failed(url, "robots.txt가 허용하지 않음")
        }
    }

    test("관련도 순으로, 페이지당 최대 2개, 수집 실패는 검색 요약으로 대신한다") {
        val result = WebRetriever(searcher, fetcher, KeywordEmbedder, "embed", perPage = 2)
            .retrieve("고양이", listOf("고양이"), limit = 10)
        result.evidence.count { it.ref == "https://a.com/cat" } shouldBe 2
        result.evidence.first().ref shouldBe "https://a.com/cat"
        result.evidence.first().location!! shouldContain "게시 2026-10-01"
        result.evidence.last().location!! shouldContain "검색 요약"
        result.pagesRead shouldBe 2
        result.failures.map { it.url } shouldContainExactly listOf("https://c.com/blocked")
    }

    test("URL을 주면 검색하지 않고 그 페이지만 읽는다") {
        searcher.queries.clear()
        val result = WebRetriever(searcher, fetcher, KeywordEmbedder, "embed").retrieve("요약", emptyList(), urls = listOf("https://a.com/cat"))
        searcher.queries.size shouldBe 0
        result.evidence.all { it.ref == "https://a.com/cat" } shouldBe true
    }
})
