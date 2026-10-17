package dev.vesta.retrieval

import dev.vesta.core.knowledge.CollectionId
import dev.vesta.storage.KnowledgeRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/**
 * 메모리 벡터 검색 (PROJECT.md 9장: 초기에는 SQLite BLOB + JVM 코사인 검색).
 * 컬렉션별로 정규화한 벡터를 캐시하고, 색인이 바뀌면 [invalidate]로 지운다.
 * 개인 문서 규모(수만 청크)는 전수 비교로 충분하다. 커지면 sqlite-vec 등으로 바꾼다.
 */
class VectorIndex(private val repository: KnowledgeRepository) {
    private class Entry(val ids: LongArray, val vectors: Array<FloatArray>)

    private val cache = mutableMapOf<CollectionId, Entry>()
    private val mutex = Mutex()

    suspend fun search(collections: List<CollectionId>, query: FloatArray, limit: Int): List<Pair<Long, Double>> {
        val q = normalize(query)
        return collections.flatMap { collection ->
            val entry = entry(collection)
            entry.ids.indices.map { i -> entry.ids[i] to dot(q, entry.vectors[i]) }
        }.sortedByDescending { it.second }.take(limit)
    }

    suspend fun invalidate(collection: CollectionId) {
        mutex.withLock { cache.remove(collection) }
    }

    private suspend fun entry(collection: CollectionId): Entry = mutex.withLock {
        cache.getOrPut(collection) {
            val rows = repository.loadVectors(listOf(collection))
            Entry(LongArray(rows.size) { rows[it].chunkId }, Array(rows.size) { normalize(rows[it].vector) })
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0 // 다른 모델로 만든 벡터
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
    }
}
