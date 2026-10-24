package dev.vesta.storage

import dev.vesta.core.UserId
import dev.vesta.core.memory.Memory
import dev.vesta.core.memory.MemoryKind
import dev.vesta.core.memory.MemoryRepository
import dev.vesta.core.memory.MemorySource
import dev.vesta.core.memory.StoredMemory
import dev.vesta.core.memory.UserQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock

/** 장기 기억 저장 (PROJECT.md 7.21, M27). 조회·수정은 사용자 범위로만 */
class ExposedMemoryRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : MemoryRepository {
    private suspend fun <T> io(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    override suspend fun add(user: UserId, text: String, kind: MemoryKind, source: MemorySource, vector: FloatArray?, model: String?): Memory = io {
        val now = clock.instant()
        val id = Memories.insertAndGetId {
            it[userId] = user.value
            it[Memories.text] = text
            it[Memories.kind] = kind.name
            it[Memories.origin] = source.name
            it[Memories.vector] = vector?.let { v -> ExposedBlob(KnowledgeRepository.encode(v)) }
            it[Memories.model] = model
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        Memories.selectAll().where { Memories.id eq id }.single().toMemory()
    }

    override suspend fun update(user: UserId, id: Long, text: String, kind: MemoryKind?, vector: FloatArray?, model: String?): Boolean = io {
        Memories.update({ (Memories.id eq id) and (Memories.userId eq user.value) }) {
            it[Memories.text] = text
            kind?.let { k -> it[Memories.kind] = k.name }
            it[Memories.vector] = vector?.let { v -> ExposedBlob(KnowledgeRepository.encode(v)) }
            it[Memories.model] = model
            it[updatedAt] = clock.instant()
        } > 0
    }

    override suspend fun revector(id: Long, vector: FloatArray, model: String) {
        io { Memories.update({ Memories.id eq id }) { it[Memories.vector] = ExposedBlob(KnowledgeRepository.encode(vector)); it[Memories.model] = model } }
    }

    override suspend fun list(user: UserId, limit: Int): List<Memory> = io {
        Memories.selectAll().where { Memories.userId eq user.value }.orderBy(Memories.updatedAt, SortOrder.DESC).limit(limit).map { it.toMemory() }
    }

    override suspend fun stored(user: UserId): List<StoredMemory> = io {
        Memories.selectAll().where { Memories.userId eq user.value }.map {
            StoredMemory(it.toMemory(), it[Memories.vector]?.bytes?.let(KnowledgeRepository::decode), it[Memories.model])
        }
    }

    override suspend fun delete(user: UserId, id: Long): Boolean = io {
        Memories.deleteWhere { (Memories.id eq id) and (Memories.userId eq user.value) } > 0
    }

    override suspend fun clear(user: UserId): Int = io { Memories.deleteWhere { Memories.userId eq user.value } }

    override suspend fun touch(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        io { Memories.update({ Memories.id inList ids }) { it[lastUsedAt] = clock.instant(); it[uses] = uses + 1 } }
    }

    override suspend fun questionsAfter(after: Long, limit: Int): List<UserQuestion> = io {
        (Messages innerJoin Sessions).select(Messages.id, Sessions.userId, Messages.content)
            .where { (Messages.id greater after) and (Messages.role eq "USER") }
            .orderBy(Messages.id, SortOrder.ASC).limit(limit)
            .map { UserQuestion(it[Messages.id].value, UserId(it[Sessions.userId].value), it[Messages.content]) }
    }

    override suspend fun cursor(): Long = io { CuratorState.selectAll().where { CuratorState.key eq CURSOR }.firstOrNull()?.get(CuratorState.value) ?: 0L }

    override suspend fun setCursor(at: Long) {
        io { CuratorState.upsert { it[key] = CURSOR; it[value] = at } }
    }

    private fun ResultRow.toMemory() = Memory(
        id = this[Memories.id].value,
        user = UserId(this[Memories.userId].value),
        text = this[Memories.text],
        kind = runCatching { MemoryKind.valueOf(this[Memories.kind]) }.getOrDefault(MemoryKind.OTHER),
        source = runCatching { MemorySource.valueOf(this[Memories.origin]) }.getOrDefault(MemorySource.AUTO),
        createdAt = this[Memories.createdAt],
        updatedAt = this[Memories.updatedAt],
        lastUsedAt = this[Memories.lastUsedAt],
        uses = this[Memories.uses],
    )

    private companion object {
        const val CURSOR = "memory_cursor"
    }
}
