package dev.vesta.core.memory

import dev.vesta.core.UserId
import java.time.Instant

/** 기억 종류 (PROJECT.md MEM-01) */
enum class MemoryKind { PROFILE, PREFERENCE, PROJECT, OTHER }

/** 직접 말했는지("기억해: …"), 대화에서 자동으로 뽑았는지 */
enum class MemorySource { EXPLICIT, AUTO }

/** 사용자에 대해 기억하는 사실 하나 (PROJECT.md 7.21) */
data class Memory(
    val id: Long,
    val user: UserId,
    val text: String,
    val kind: MemoryKind,
    val source: MemorySource,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastUsedAt: Instant? = null,
    val uses: Int = 0,
)

/** 검색용: 기억과 그 벡터를 만든 임베딩 모델 */
class StoredMemory(val memory: Memory, val vector: FloatArray?, val model: String?)

interface MemoryRepository {
    suspend fun add(user: UserId, text: String, kind: MemoryKind, source: MemorySource, vector: FloatArray?, model: String?): Memory
    /** 글을 바꾼다 ([kind]가 null이면 그대로). 본인 것이 아니면 false */
    suspend fun update(user: UserId, id: Long, text: String, kind: MemoryKind?, vector: FloatArray?, model: String?): Boolean
    /** 벡터만 다시 (임베딩 모델이 바뀌었을 때) */
    suspend fun revector(id: Long, vector: FloatArray, model: String)
    /** 최근에 바뀐 것부터 */
    suspend fun list(user: UserId, limit: Int = 500): List<Memory>
    /** 검색할 기억 전부와 벡터 (사용자당 500개까지라 모두 읽어 비교한다) */
    suspend fun stored(user: UserId): List<StoredMemory>
    suspend fun delete(user: UserId, id: Long): Boolean
    suspend fun clear(user: UserId): Int
    /** 답에 썼다 */
    suspend fun touch(ids: Collection<Long>)

    /** 자동 기억 (MEM-03): [after]보다 뒤의 사용자 질문 (ID 순) */
    suspend fun questionsAfter(after: Long, limit: Int): List<UserQuestion>
    suspend fun cursor(): Long
    suspend fun setCursor(at: Long)
}

/** 자동 기억이 읽는 질문 */
data class UserQuestion(val id: Long, val user: UserId, val text: String)
