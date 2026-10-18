package dev.vesta.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.knowledge.RetrieveOptions
import dev.vesta.core.knowledge.Retriever
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import dev.vesta.core.web.FetchResult
import dev.vesta.core.web.PageFetcher
import dev.vesta.core.web.WebSearchClient
import dev.vesta.storage.KnowledgeRepository
import kotlinx.serialization.json.JsonObject

/** 등록한 문서·코드 컬렉션 검색 (T-05). */
class SearchDocsTool(private val knowledge: KnowledgeRepository, private val retriever: Retriever) : Tool {
    override val spec = ToolSpec(
        "search_docs",
        "사용자가 등록한 문서·코드 컬렉션에서 관련 내용을 찾는다. 결과에 파일 경로와 위치가 붙는다.",
        schema("query" to prop("string", "찾을 내용"), "collection" to prop("string", "컬렉션 이름 (생략하면 전체)"), required = listOf("query")),
    )
    override val risk = RiskLevel.READ

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.string("query") ?: return fail("query가 필요함")
        val all = knowledge.listCollections(context.user.userId)
        val targets = arguments.string("collection")?.let { name -> all.filter { it.name == name } } ?: all
        if (targets.isEmpty()) return fail("컬렉션이 없음 (있는 것: ${all.joinToString { it.name }.ifEmpty { "없음" }})")
        val hits = retriever.retrieve(context.user.userId, listOf(query), RetrieveOptions(targets.map { it.id }, 6))
        if (hits.isEmpty()) return ToolResult("찾은 내용 없음", summary = "결과 없음")
        return ToolResult(
            hits.mapIndexed { i, hit -> "[${i + 1}] ${hit.title} (${hit.location}) — ${hit.ref}\n${hit.text.take(1_200)}" }.joinToString("\n\n"),
            summary = "검색 결과 ${hits.size}건",
        )
    }
}

/** 웹 검색 (위험 등급 NETWORK: 외부로 검색어가 나간다, 5.4). */
class WebSearchTool(private val search: WebSearchClient) : Tool {
    override val spec = ToolSpec(
        "web_search",
        "인터넷 검색 결과(제목·URL·요약)를 돌려준다. 내용을 자세히 보려면 read_url을 쓴다. 검색어에 개인 정보나 로컬 파일 내용을 넣지 않는다.",
        schema("query" to prop("string", "검색어"), required = listOf("query")),
    )
    override val risk = RiskLevel.NETWORK

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.string("query") ?: return fail("query가 필요함")
        val hits = search.search(query, 8)
        return ToolResult(
            if (hits.isEmpty()) "검색 결과 없음" else hits.mapIndexed { i, h -> "[${i + 1}] ${h.title}\n${h.url}\n${h.snippet.take(300)}" }.joinToString("\n\n"),
            summary = "\"$query\" 검색 ${hits.size}건",
        )
    }
}

/** 웹 페이지 본문 읽기 (NETWORK, SSRF 방지는 PageFetcher가 한다). */
class ReadUrlTool(private val fetcher: PageFetcher, private val maxChars: Int) : Tool {
    override val spec = ToolSpec("read_url", "웹 페이지 본문을 읽는다.", schema("url" to prop("string", "http(s) URL"), required = listOf("url")))
    override val risk = RiskLevel.NETWORK

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val url = arguments.string("url") ?: return fail("url이 필요함")
        return when (val result = fetcher.fetch(url)) {
            is FetchResult.Failed -> fail("읽지 못함: ${result.reason}")
            is FetchResult.Ok -> ToolResult(
                "${result.page.title}\n${result.page.url}\n\n${result.page.text.take(maxChars)}",
                summary = "${result.page.title} (${result.page.text.length}자)",
            )
        }
    }
}
