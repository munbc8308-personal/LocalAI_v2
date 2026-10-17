package dev.vesta.storage

import dev.vesta.core.web.WebPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Duration

data class SearchLogEntry(val query: String, val backend: String, val resultCount: Int, val at: java.time.Instant)

/** 웹 페이지 TTL 캐시와 검색어 기록. */
class WebCacheRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    /** 만료되지 않은 캐시만 돌려준다. */
    suspend fun find(url: String): WebPage? = io {
        WebPages.selectAll().where { WebPages.url eq url }.firstOrNull()
            ?.takeIf { it[WebPages.expiresAt].isAfter(clock.instant()) }
            ?.let { WebPage(it[WebPages.url], it[WebPages.title], it[WebPages.text], it[WebPages.fetchedAt], it[WebPages.publishedDate]) }
    }

    suspend fun save(page: WebPage, ttl: Duration) = io {
        WebPages.upsert(WebPages.url) {
            it[url] = page.url
            it[title] = page.title
            it[text] = page.text
            it[publishedDate] = page.publishedDate
            it[fetchedAt] = page.fetchedAt
            it[expiresAt] = page.fetchedAt.plus(ttl)
        }
        WebPages.deleteWhere { expiresAt less clock.instant() }
    }

    suspend fun logSearch(query: String, backend: String, resultCount: Int) = io {
        WebSearchLogs.insert {
            it[WebSearchLogs.query] = query
            it[WebSearchLogs.backend] = backend
            it[WebSearchLogs.resultCount] = resultCount
            it[createdAt] = clock.instant()
        }
    }

    suspend fun recentSearches(limit: Int): List<SearchLogEntry> = io {
        WebSearchLogs.selectAll().orderBy(WebSearchLogs.id, SortOrder.DESC).limit(limit).map {
            SearchLogEntry(it[WebSearchLogs.query], it[WebSearchLogs.backend], it[WebSearchLogs.resultCount], it[WebSearchLogs.createdAt])
        }
    }
}
