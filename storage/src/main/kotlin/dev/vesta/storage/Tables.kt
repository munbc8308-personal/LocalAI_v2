package dev.vesta.storage

import dev.vesta.core.UserRole
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 사용자. 멀티 유저는 M15에서 켜지만 `userId`는 M1부터 모든 사용자 데이터에 넣는다 (PROJECT.md 11장).
 * 나머지 테이블(Session, Message, ...)은 해당 마일스톤에서 추가한다.
 */
object Users : LongIdTable("users") {
    val name = varchar("name", 100).uniqueIndex()
    val role = enumerationByName("role", 16, UserRole::class)
    val passwordHash = varchar("password_hash", 255).nullable()
    val trainingConsent = bool("training_consent").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}

/** 스키마 버전. 마이그레이션 순서 관리용. */
object SchemaVersion : LongIdTable("schema_version") {
    val version = integer("version")
    val appliedAt = timestamp("applied_at").defaultExpression(CurrentTimestamp)
}

/** 대화 세션 (PROJECT.md 11장 Session). */
object Sessions : org.jetbrains.exposed.v1.core.Table("sessions") {
    val id = varchar("id", 36)
    val userId = reference("user_id", Users)
    val title = varchar("title", 200).nullable()
    val originChannel = varchar("origin_channel", 32)
    val systemPrompt = text("system_prompt").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").index()
    override val primaryKey = PrimaryKey(id)
}

/** 메시지 (PROJECT.md 11장 Message). */
object Messages : LongIdTable("messages") {
    val sessionId = reference("session_id", Sessions.id, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).index()
    val role = varchar("role", 16)
    val content = text("content")
    val agent = varchar("agent", 16).nullable()
    val model = varchar("model", 200).nullable()
    val interrupted = bool("interrupted").default(false)
    val promptTokens = integer("prompt_tokens").nullable()
    val completionTokens = integer("completion_tokens").nullable()
    val createdAt = timestamp("created_at")
}

/** 지식 베이스 컬렉션 (PROJECT.md K-01, 11장 Collection). */
object Collections : LongIdTable("collections") {
    val ownerUserId = reference("owner_user_id", Users)
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val embeddingModel = varchar("embedding_model", 200)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(ownerUserId, name)
    }
}

/** 색인한 파일. contentHash로 변경 여부를 판단한다 (K-10). */
object Documents : LongIdTable("documents") {
    val collectionId = reference("collection_id", Collections, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val path = text("path")
    val title = text("title")
    val contentHash = varchar("content_hash", 64)
    val indexedAt = timestamp("indexed_at")

    init {
        uniqueIndex(collectionId, path)
    }
}

/** 청크. 키워드 검색은 FTS5 가상 테이블 chunks_fts(rowid = chunks.id)가 맡는다. */
object Chunks : LongIdTable("chunks") {
    val documentId = reference("document_id", Documents, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).index()
    val ordinal = integer("ordinal")
    val text = text("text")
    val location = varchar("location", 100).nullable()
    val heading = text("heading").nullable()
    val embedding = binary("embedding")
}

/** 웹 페이지 본문 캐시 (PROJECT.md W-07, 11장 WebPage). */
object WebPages : LongIdTable("web_pages") {
    val url = text("url").uniqueIndex()
    val title = text("title")
    val text = text("text")
    val publishedDate = varchar("published_date", 64).nullable()
    val fetchedAt = timestamp("fetched_at")
    val expiresAt = timestamp("expires_at")
}

/** 외부로 보낸 검색어 기록 (PROJECT.md 5.4, W-06, 11장 WebSearchLog). */
object WebSearchLogs : LongIdTable("web_search_log") {
    val query = text("query")
    val backend = varchar("backend", 32)
    val resultCount = integer("result_count")
    val createdAt = timestamp("created_at")
}
