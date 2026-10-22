package dev.vesta.evolution

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** 라이브러리의 모델 계열 하나 ("새로 나온 모델" 목록의 한 줄) */
data class CatalogFamily(val name: String, val capabilities: Set<String>, val sizes: List<String>)

/** 계열의 태그 하나. [digest]는 앞 12자리 */
data class CatalogTag(val name: String, val digest: String, val sizeBytes: Long)

/** 새 모델을 찾는 곳 (E-02). 테스트에서는 가짜를 쓴다 */
interface ModelCatalog {
    suspend fun newest(): List<CatalogFamily>
    suspend fun tags(family: String): List<CatalogTag>
}

/**
 * Ollama 라이브러리 웹 페이지(ollama.com)를 읽는다. 공개 API가 없어 HTML에서 필요한 부분만 뽑는다:
 * 페이지 구조가 바뀌면 빈 목록이 나오고, 점검 보고서에 "모델 목록을 읽지 못함"으로 남는다
 */
class OllamaLibraryCatalog(private val baseUrl: String, private val http: HttpClient) : ModelCatalog {
    override suspend fun newest(): List<CatalogFamily> = OllamaLibraryHtml.families(page("/search?o=newest"))

    override suspend fun tags(family: String): List<CatalogTag> {
        require(FAMILY.matches(family)) { "모델 계열 이름이 이상합니다: $family" }
        return OllamaLibraryHtml.tags(page("/library/$family/tags"))
    }

    private suspend fun page(path: String): String {
        val response = http.get(baseUrl.trimEnd('/') + path) { header("Accept", "text/html") }
        check(response.status.isSuccess()) { "모델 목록을 읽지 못했습니다 ($path: ${response.status})" }
        return response.bodyAsText()
    }

    private companion object {
        val FAMILY = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    }
}

/** ollama.com HTML 해석 (2026-10 구조 기준) */
object OllamaLibraryHtml {
    private val ITEM = Regex("""<a href="/library/([a-z0-9][a-z0-9._-]*)" class="group flex""")
    private val CAPABILITY = Regex("""text-indigo-600[^"]*">([a-z]+)</span>""")
    private val SIZE = Regex("""text-blue-600[^"]*">([0-9.]+[a-z]+)</span>""")
    private val TAG = Regex(
        """<a href="/library/([a-z0-9][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*)" class="md:hidden[^"]*"(?s:.{0,1500}?)font-mono">\s*([0-9a-f]{12})\s*</span>\s*•\s*([0-9.]+)\s*(KB|MB|GB|TB)""",
    )

    fun families(html: String): List<CatalogFamily> {
        val starts = ITEM.findAll(html).toList()
        return starts.mapIndexed { i, match ->
            val block = html.substring(match.range.first, starts.getOrNull(i + 1)?.range?.first ?: minOf(html.length, match.range.first + 6_000))
            CatalogFamily(
                name = match.groupValues[1],
                capabilities = CAPABILITY.findAll(block).map { it.groupValues[1] }.toSet(),
                sizes = SIZE.findAll(block).map { it.groupValues[1] }.toList(),
            )
        }.distinctBy { it.name }
    }

    fun tags(html: String): List<CatalogTag> =
        TAG.findAll(html).map { m ->
            val unit = when (m.groupValues[4]) { "KB" -> 1e3; "MB" -> 1e6; "GB" -> 1e9; else -> 1e12 }
            CatalogTag(m.groupValues[1], m.groupValues[2], (m.groupValues[3].toDouble() * unit).toLong())
        }.distinctBy { it.name }.toList()
}
