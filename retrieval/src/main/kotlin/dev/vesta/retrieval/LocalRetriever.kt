package dev.vesta.retrieval

import dev.vesta.core.SourceType
import dev.vesta.core.UserId
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.knowledge.Reranker
import dev.vesta.core.knowledge.RetrieveOptions
import dev.vesta.core.knowledge.Retriever
import dev.vesta.knowledge.EmbeddingProfile
import dev.vesta.storage.ChunkRecord
import dev.vesta.storage.KnowledgeRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * 로컬 문서 하이브리드 검색 (PROJECT.md K-06·K-07, 5.1 [2]~[3]).
 * 질의마다 벡터 검색과 키워드(BM25) 검색을 하고 RRF로 합친 뒤, 리랭커가 있으면 다시 정렬한다.
 */
class LocalRetriever(
    private val repository: KnowledgeRepository,
    private val inference: InferenceClient,
    private val vectors: VectorIndex,
    private val reranker: Reranker? = null,
    private val candidatesPerList: Int = 30,
    private val rerankPool: Int = 24,
) : Retriever {
    override val source = SourceType.LOCAL_DOCS

    override suspend fun retrieve(user: UserId, queries: List<String>, options: RetrieveOptions): List<Evidence> {
        val owned = repository.accessibleCollections(user).filter { it.id in options.collections }
        if (owned.isEmpty() || queries.isEmpty()) return emptyList()

        val rankings = mutableListOf<List<Long>>()
        for ((model, group) in owned.groupBy { it.embeddingModel }) {
            val ids = group.map { it.id }
            val profile = EmbeddingProfile.of(model)
            val queryVectors = inference.embed(model, queries.map(profile::query))
            queryVectors.forEach { rankings += vectors.search(ids, it, candidatesPerList).map { hit -> hit.first } }
            queries.forEach { query ->
                rankings += repository.keywordSearch(ids, KeywordQuery.match(query), candidatesPerList).map { it.first }
            }
        }
        val fused = Rrf.fuse(rankings).take(maxOf(rerankPool, options.limit))
        val records = repository.chunks(fused.map { it.first }).associateBy { it.id }
        val candidates = fused.mapNotNull { (id, score) -> records[id]?.toEvidence(score) }

        val ranked = reranker?.let {
            runCatching { it.rerank(queries.first(), candidates, options.limit) }
                .onFailure { e -> log.warn(e) { "리랭크 실패, RRF 순서 사용" } }
                .getOrNull()
        } ?: candidates
        return ranked.take(options.limit)
    }

    private fun ChunkRecord.toEvidence(score: Double) = Evidence(
        source = SourceType.LOCAL_DOCS,
        ref = documentPath,
        title = documentTitle,
        location = listOfNotNull(heading?.substringAfterLast(" > "), location).joinToString(" · ").ifEmpty { null },
        text = text,
        score = score,
    )
}

/** Reciprocal Rank Fusion: 여러 순위 목록을 점수 척도와 상관없이 합친다. */
internal object Rrf {
    fun fuse(rankings: List<List<Long>>, k: Int = 60): List<Pair<Long, Double>> {
        val scores = mutableMapOf<Long, Double>()
        rankings.forEach { list -> list.forEachIndexed { rank, id -> scores.merge(id, 1.0 / (k + rank + 1), Double::plus) } }
        return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
