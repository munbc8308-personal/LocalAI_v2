package dev.vesta.storage

import dev.vesta.core.UserId
import dev.vesta.core.knowledge.Collection
import dev.vesta.core.knowledge.CollectionId
import dev.vesta.core.knowledge.CollectionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock

data class NewChunk(val text: String, val location: String?, val heading: String?, val embedding: FloatArray)

data class DocumentRecord(val id: Long, val path: String, val contentHash: String)

data class ChunkVector(val chunkId: Long, val vector: FloatArray)

data class ChunkRecord(
    val id: Long,
    val collectionId: CollectionId,
    val documentPath: String,
    val documentTitle: String,
    val location: String?,
    val heading: String?,
    val text: String,
)

data class CollectionStats(val documents: Long, val chunks: Long)

/** 컬렉션·문서·청크 저장과 키워드 검색 (PROJECT.md K-01~K-06). 조회는 소유자 범위로 제한한다. */
class KnowledgeRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private suspend fun <T> io(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    suspend fun createCollection(
        owner: UserId,
        name: String,
        description: String?,
        embeddingModel: String,
        kind: CollectionKind = CollectionKind.DOCS,
        rootPath: String? = null,
    ): Collection = io {
        val id = Collections.insertAndGetId {
            it[ownerUserId] = EntityID(owner.value, Users)
            it[Collections.name] = name
            it[Collections.description] = description
            it[Collections.embeddingModel] = embeddingModel
            it[Collections.kind] = kind.name
            it[Collections.rootPath] = rootPath
            it[createdAt] = clock.instant()
        }.value
        Collection(CollectionId(id), owner, name, description, embeddingModel, kind, rootPath)
    }

    suspend fun findCollection(owner: UserId, name: String): Collection? = io {
        Collections.selectAll().where { (Collections.ownerUserId eq owner.value) and (Collections.name eq name) }
            .firstOrNull()?.toCollection()
    }

    /** 내가 소유한 컬렉션 (관리용: 추가·삭제·공유) */
    suspend fun listCollections(owner: UserId): List<Collection> = io {
        Collections.selectAll().where { Collections.ownerUserId eq owner.value }.orderBy(Collections.name).map { it.toCollection() }
    }

    /** 검색할 수 있는 컬렉션 = 내 것 + 나에게 공유된 것 (U-04). 검색·라우팅은 이것만 쓴다 */
    suspend fun accessibleCollections(user: UserId): List<Collection> = io {
        val shared = CollectionShares.select(CollectionShares.collectionId).where { CollectionShares.userId eq user.value }.map { it[CollectionShares.collectionId].value }
        Collections.selectAll().where { (Collections.ownerUserId eq user.value) or (Collections.id inList shared) }
            .orderBy(Collections.name).map { it.toCollection() }
    }

    /** 소유자만 공유 대상을 바꿀 수 있다 */
    suspend fun share(owner: UserId, id: CollectionId, with: UserId): Boolean = io {
        if (with == owner || !owns(owner, id)) return@io false
        CollectionShares.insertIgnore {
            it[collectionId] = EntityID(id.value, Collections)
            it[userId] = EntityID(with.value, Users)
        }
        true
    }

    suspend fun unshare(owner: UserId, id: CollectionId, with: UserId): Boolean = io {
        owns(owner, id) && CollectionShares.deleteWhere { (collectionId eq id.value) and (userId eq with.value) } > 0
    }

    private fun owns(owner: UserId, id: CollectionId) =
        Collections.selectAll().where { (Collections.id eq id.value) and (Collections.ownerUserId eq owner.value) }.any()

    /** 컬렉션과 그 문서·청크·키워드 색인을 지운다. */
    suspend fun deleteCollection(owner: UserId, id: CollectionId): Boolean = io {
        val owned = Collections.selectAll().where { (Collections.id eq id.value) and (Collections.ownerUserId eq owner.value) }.any()
        if (!owned) return@io false
        val docIds = Documents.select(Documents.id).where { Documents.collectionId eq id.value }.map { it[Documents.id].value }
        docIds.forEach { deleteFts(it) }
        Collections.deleteWhere { Collections.id eq id.value }
        true
    }

    suspend fun stats(id: CollectionId): CollectionStats = io {
        val docIds = Documents.select(Documents.id).where { Documents.collectionId eq id.value }.map { it[Documents.id].value }
        val chunks = if (docIds.isEmpty()) 0 else Chunks.selectAll().where { Chunks.documentId inList docIds }.count()
        CollectionStats(docIds.size.toLong(), chunks)
    }

    suspend fun findDocument(collection: CollectionId, path: String): DocumentRecord? = io {
        Documents.selectAll().where { (Documents.collectionId eq collection.value) and (Documents.path eq path) }
            .firstOrNull()?.let { DocumentRecord(it[Documents.id].value, it[Documents.path], it[Documents.contentHash]) }
    }

    suspend fun listDocuments(collection: CollectionId): List<DocumentRecord> = io {
        Documents.selectAll().where { Documents.collectionId eq collection.value }
            .map { DocumentRecord(it[Documents.id].value, it[Documents.path], it[Documents.contentHash]) }
    }

    /** 문서를 새 청크로 통째로 바꾼다 (재색인). 한 트랜잭션으로 처리한다. */
    suspend fun replaceDocument(collection: CollectionId, path: String, title: String, contentHash: String, chunks: List<NewChunk>) = io {
        Documents.selectAll().where { (Documents.collectionId eq collection.value) and (Documents.path eq path) }
            .firstOrNull()?.let { existing ->
                val docId = existing[Documents.id].value
                deleteFts(docId)
                Documents.deleteWhere { Documents.id eq docId }
            }
        val docId = Documents.insertAndGetId {
            it[collectionId] = EntityID(collection.value, Collections)
            it[Documents.path] = path
            it[Documents.title] = title
            it[Documents.contentHash] = contentHash
            it[indexedAt] = clock.instant()
        }
        chunks.forEachIndexed { index, chunk ->
            val chunkId = Chunks.insertAndGetId {
                it[documentId] = docId
                it[ordinal] = index
                it[text] = chunk.text
                it[location] = chunk.location
                it[heading] = chunk.heading
                it[embedding] = encode(chunk.embedding)
            }.value
            exec(
                "INSERT INTO chunks_fts(rowid, text, heading) VALUES (?, ?, ?)",
                listOf(LongColumnType() to chunkId, TextColumnType() to chunk.text, TextColumnType() to chunk.heading.orEmpty()),
            )
        }
    }

    suspend fun removeDocument(collection: CollectionId, path: String): Boolean = io {
        val doc = Documents.selectAll().where { (Documents.collectionId eq collection.value) and (Documents.path eq path) }.firstOrNull()
            ?: return@io false
        val docId = doc[Documents.id].value
        deleteFts(docId)
        Documents.deleteWhere { Documents.id eq docId }
        true
    }

    /** 벡터 검색용으로 컬렉션의 모든 청크 벡터를 읽는다. */
    suspend fun loadVectors(collections: List<CollectionId>): List<ChunkVector> = io {
        (Chunks innerJoin Documents)
            .select(Chunks.id, Chunks.embedding)
            .where { Documents.collectionId inList collections.map { it.value } }
            .map { ChunkVector(it[Chunks.id].value, decode(it[Chunks.embedding])) }
    }

    /** FTS5 BM25 검색. [match]는 FTS5 MATCH 문법. 점수는 클수록 관련 높음(부호를 뒤집어 돌려준다). */
    suspend fun keywordSearch(collections: List<CollectionId>, match: String, limit: Int): List<Pair<Long, Double>> = io {
        if (collections.isEmpty() || match.isBlank()) return@io emptyList()
        val placeholders = collections.joinToString(",") { "?" }
        val args = buildList {
            add(TextColumnType() to match)
            collections.forEach { add(LongColumnType() to it.value) }
            add(LongColumnType() to limit.toLong())
        }
        exec(
            """
            SELECT f.rowid, bm25(chunks_fts) AS score FROM chunks_fts f
            JOIN chunks c ON c.id = f.rowid JOIN documents d ON d.id = c.document_id
            WHERE chunks_fts MATCH ? AND d.collection_id IN ($placeholders)
            ORDER BY score LIMIT ?
            """.trimIndent(),
            args,
        ) { rs ->
            buildList { while (rs.next()) add(rs.getLong(1) to -rs.getDouble(2)) }
        } ?: emptyList()
    }

    suspend fun chunks(ids: List<Long>): List<ChunkRecord> = io {
        if (ids.isEmpty()) return@io emptyList()
        val rows = (Chunks innerJoin Documents).selectAll().where { Chunks.id inList ids }.associateBy { it[Chunks.id].value }
        ids.mapNotNull { id ->
            rows[id]?.let {
                ChunkRecord(
                    id = id,
                    collectionId = CollectionId(it[Documents.collectionId].value),
                    documentPath = it[Documents.path],
                    documentTitle = it[Documents.title],
                    location = it[Chunks.location],
                    heading = it[Chunks.heading],
                    text = it[Chunks.text],
                )
            }
        }
    }

    private fun JdbcTransaction.deleteFts(documentId: Long) {
        exec(
            "DELETE FROM chunks_fts WHERE rowid IN (SELECT id FROM chunks WHERE document_id = ?)",
            listOf(LongColumnType() to documentId),
        )
    }

    private fun ResultRow.toCollection(): Collection {
        val id = this[Collections.id].value
        val shared = CollectionShares.select(CollectionShares.userId).where { CollectionShares.collectionId eq id }.map { UserId(it[CollectionShares.userId].value) }.toSet()
        return toCollection(shared)
    }

    private fun ResultRow.toCollection(sharedWith: Set<UserId>) = Collection(
        id = CollectionId(this[Collections.id].value),
        ownerUserId = UserId(this[Collections.ownerUserId].value),
        name = this[Collections.name],
        description = this[Collections.description],
        embeddingModel = this[Collections.embeddingModel],
        kind = CollectionKind.valueOf(this[Collections.kind]),
        rootPath = this[Collections.rootPath],
        sharedWith = sharedWith,
    )

    companion object {
        fun encode(vector: FloatArray): ByteArray =
            ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat(it) } }.array()

        fun decode(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.getFloat() }
        }
    }
}
