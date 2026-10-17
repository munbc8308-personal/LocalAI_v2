package dev.vesta.web

import dev.vesta.core.config.WebConfig
import dev.vesta.core.web.FetchResult
import dev.vesta.core.web.PageFetcher
import dev.vesta.core.web.WebPage
import dev.vesta.storage.WebCacheRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import java.net.URI
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 웹 페이지 수집 (PROJECT.md W-02, 5.3 제한·예의·도메인 정책).
 * 캐시 → 차단 도메인 → robots.txt → 받기(타임아웃, 크기 제한) → 본문 추출 → 캐시 저장.
 */
class HttpPageFetcher(
    private val http: HttpClient,
    private val config: WebConfig,
    private val cache: WebCacheRepository?,
    private val clock: Clock = Clock.systemUTC(),
    private val maxBytes: Long = 2L * 1024 * 1024,
) : PageFetcher {
    private val robots = RobotsCache(http, config.userAgent)

    override suspend fun fetch(url: String): FetchResult {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase() ?: return FetchResult.Failed(url, "잘못된 URL")
        if (uri.scheme !in setOf("http", "https")) return FetchResult.Failed(url, "http(s)만 지원")
        if (isBlocked(host)) return FetchResult.Failed(url, "차단 도메인")
        if (isPrivateHost(host)) return FetchResult.Failed(url, "내부 네트워크 주소는 읽지 않음")
        cache?.find(url)?.let { return FetchResult.Ok(it, fromCache = true) }
        if (!robots.allowed(uri)) return FetchResult.Failed(url, "robots.txt가 허용하지 않음")

        return runCatching {
            withTimeout(config.pageTimeoutSeconds * 1_000) {
                val response = http.get(url) {
                    header(HttpHeaders.UserAgent, config.userAgent)
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml,text/plain;q=0.8")
                    header(HttpHeaders.AcceptLanguage, "ko,en;q=0.8")
                }
                page(url, response)
            }
        }.getOrElse {
            log.debug(it) { "수집 실패: $url" }
            FetchResult.Failed(url, it.message ?: it::class.simpleName.orEmpty())
        }
    }

    private suspend fun page(url: String, response: HttpResponse): FetchResult {
        if (!response.status.isSuccess()) return FetchResult.Failed(url, "HTTP ${response.status.value}")
        val type = response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" }.orEmpty()
        if (type.isNotEmpty() && type != "text/html" && type != "application/xhtml+xml" && type != "text/plain") {
            return FetchResult.Failed(url, "지원하지 않는 형식: $type")
        }
        val bytes = response.bodyAsChannel().readRemaining(maxBytes).readByteArray()
        val charset = response.contentType()?.parameter("charset") ?: "UTF-8"
        val body = runCatching { String(bytes, charset(charset)) }.getOrElse { bytes.decodeToString() }
        val finalUrl = response.request.url.toString()
        val extracted = if (type == "text/plain") ContentExtractor.plainText(body) else ContentExtractor.extract(body, finalUrl)
        if (extracted.text.length < 100) return FetchResult.Failed(url, "본문이 거의 없음 (동적 페이지일 수 있음)")
        val page = WebPage(url, extracted.title.ifBlank { URI(finalUrl).host }, extracted.text, clock.instant(), extracted.publishedDate)
        cache?.save(page, Duration.ofHours(config.cacheHours))
        return FetchResult.Ok(page, fromCache = false)
    }

    private fun isBlocked(host: String) = config.blockedDomains.any { host == it || host.endsWith(".$it") }

    /** 웹 페이지에 적힌 링크로 내부 서비스(Ollama, SearXNG 등)를 읽게 만드는 것을 막는다. */
    private fun isPrivateHost(host: String): Boolean =
        host == "localhost" || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("10.") ||
            host.startsWith("192.168.") || host.startsWith("169.254.") || host == "0.0.0.0" || host == "[::1]" ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
}

/** 호스트별 robots.txt 캐시. 받지 못하면 허용으로 본다. */
internal class RobotsCache(private val http: HttpClient, private val userAgent: String) {
    private val rules = mutableMapOf<String, List<String>>()

    suspend fun allowed(uri: URI): Boolean {
        val origin = "${uri.scheme}://${uri.authority}"
        val disallow = rules[origin] ?: load(origin).also { rules[origin] = it }
        val path = uri.rawPath.ifEmpty { "/" }
        return disallow.none { it.isNotEmpty() && path.startsWith(it) }
    }

    private suspend fun load(origin: String): List<String> = runCatching {
        withTimeout(5_000) {
            val response = http.get("$origin/robots.txt") { header(HttpHeaders.UserAgent, userAgent) }
            if (!response.status.isSuccess()) emptyList() else Robots.disallowed(response.bodyAsText(), "vestabot")
        }
    }.getOrDefault(emptyList())
}

internal object Robots {
    /** `User-agent: *` 또는 우리 봇 이름에 해당하는 Disallow 경로 목록. 우리 봇 전용 규칙이 있으면 그것을 쓴다. */
    fun disallowed(text: String, agent: String): List<String> {
        val groups = mutableMapOf<String, MutableList<String>>()
        var agents = mutableListOf<String>()
        var lastWasAgent = false
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "user-agent" -> {
                    if (!lastWasAgent) agents = mutableListOf()
                    agents += value.lowercase()
                    agents.forEach { groups.getOrPut(it) { mutableListOf() } }
                    lastWasAgent = true
                }
                "disallow" -> {
                    agents.forEach { groups.getOrPut(it) { mutableListOf() }.add(value) }
                    lastWasAgent = false
                }
                else -> lastWasAgent = false
            }
        }
        return groups.entries.firstOrNull { agent.startsWith(it.key) && it.key != "*" }?.value ?: groups["*"].orEmpty()
    }
}
