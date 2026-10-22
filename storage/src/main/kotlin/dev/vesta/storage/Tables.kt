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
    /** DOCS / CODE (schema v6에서 추가) */
    val kind = varchar("kind", 16).default("DOCS")
    val rootPath = text("root_path").nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(ownerUserId, name)
    }
}

/** 컬렉션 공유 대상 (PROJECT.md U-04, v13). 한 줄도 없으면 PRIVATE(소유자만). 색인은 소유자 것 하나를 같이 쓴다 */
object CollectionShares : org.jetbrains.exposed.v1.core.Table("collection_share") {
    val collectionId = reference("collection_id", Collections, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val userId = reference("user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).index()
    override val primaryKey = PrimaryKey(collectionId, userId)
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

/** 라우팅 기록 (PROJECT.md 11장 RoutingLog). 보정·평가·학습 데이터(L-01)용. */
object RoutingLogs : LongIdTable("routing_log") {
    val sessionId = varchar("session_id", 36).index()
    val question = text("question")
    val agent = varchar("agent", 16)
    val requested = varchar("requested", 16).nullable()
    val stage = varchar("stage", 16)
    val confidence = double("confidence")
    val needsWeb = double("needs_web").nullable()
    val needsLocal = double("needs_local").nullable()
    val latencyMs = long("latency_ms")
    val userOverride = varchar("user_override", 16).nullable()
    val createdAt = timestamp("created_at")
}

/** 도구 실행 기록 (PROJECT.md T-08, 11장 ToolRun). */
object ToolRuns : LongIdTable("tool_run") {
    val userId = long("user_id").index()
    val sessionId = varchar("session_id", 36).index()
    val tool = varchar("tool", 64)
    val arguments = text("arguments")
    val risk = varchar("risk", 16)
    val approved = bool("approved").nullable()
    val isError = bool("is_error")
    val result = text("result")
    val durationMs = long("duration_ms")
    val createdAt = timestamp("created_at")
}

/**
 * 웹 UI 로그인 세션 (M9, PROJECT.md 6.3·U-02). 쿠키 값은 저장하지 않고 SHA-256 해시만 둔다.
 * [csrf]는 쿠키로 인증한 POST 요청이 `X-Vesta-CSRF` 헤더로 함께 보내야 하는 값.
 */
object WebSessions : LongIdTable("web_session") {
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val userId = long("user_id").index()
    val csrf = varchar("csrf", 64)
    val userAgent = varchar("user_agent", 255).nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
}

/** 메신저 계정 ↔ 사용자 연결 (M10, PROJECT.md 6.5·11장 ChannelBinding) */
object ChannelBindings : LongIdTable("channel_binding") {
    val userId = long("user_id").index()
    val channel = varchar("channel", 16)
    val externalUserId = varchar("external_user_id", 64)
    val externalChatId = varchar("external_chat_id", 64)
    val sessionId = varchar("session_id", 36).nullable()
    val allowed = bool("allowed").default(true)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(channel, externalUserId)
    }
}

/** 메신저 연결용 일회용 코드. SHA-256만 저장한다 */
object ChannelPairings : LongIdTable("channel_pairing") {
    val userId = long("user_id")
    val channel = varchar("channel", 16)
    val codeHash = varchar("code_hash", 64)
    val expiresAt = timestamp("expires_at")
}

/** 미디어 (PROJECT.md 11장 MediaAsset, M12). 파일은 `~/.vesta/media/<사용자>/`에, 여기는 정보만 */
object MediaAssets : org.jetbrains.exposed.v1.core.Table("media_asset") {
    val id = varchar("id", 36)
    val userId = long("user_id").index()
    /** 붙인 메시지. 메시지를 지우면(세션 삭제) 함께 지운다 */
    val messageId = reference("message_id", Messages.id, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).nullable().index()
    val kind = varchar("kind", 16)
    val mime = varchar("mime", 64)
    val path = text("path")
    val size = long("size")
    /** 생성 프롬프트 등 (v11) */
    val caption = text("caption").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** 답변 피드백 (PROJECT.md C-09, v12). 답변 메시지 하나에 하나 */
object MessageFeedbacks : org.jetbrains.exposed.v1.core.Table("message_feedback") {
    val messageId = reference("message_id", Messages.id, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val userId = long("user_id").index()
    val rating = integer("rating")
    val correctAgent = varchar("correct_agent", 16).nullable()
    val comment = text("comment").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(messageId)
}

/** 학습 실행 (PROJECT.md 11장 TrainingRun, v12) */
object TrainingRuns : LongIdTable("training_run") {
    val kind = varchar("kind", 16)
    val status = varchar("status", 16)
    val baseModel = varchar("base_model", 200)
    val step = integer("step").default(0)
    val total = integer("total").default(0)
    val loss = double("loss").nullable()
    val stage = varchar("stage", 32).nullable()
    val candidate = varchar("candidate", 200).nullable()
    val report = text("report").nullable()
    val error = text("error").nullable()
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
}

/** 모델 버전 (PROJECT.md 11장 ModelVersion, v12) */
object ModelVersions : LongIdTable("model_version") {
    val role = varchar("role", 16).index()
    val name = varchar("name", 200)
    val baseModel = varchar("base_model", 200)
    val runId = long("run_id").nullable()
    val active = bool("active").default(false)
    val metrics = text("metrics").nullable()
    val createdAt = timestamp("created_at")
}
