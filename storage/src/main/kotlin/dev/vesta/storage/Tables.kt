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
    /** 바깥 접속(Cloudflare Access) 로그인 이메일 (M26). 소문자로 저장 */
    val email = varchar("email", 254).nullable()
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
/** 영상 프로젝트 (M24 G-05, v22): 계획·장면 재료 ID를 JSON으로. 대화별 마지막 것을 고친다 */
object VideoProjects : org.jetbrains.exposed.v1.core.Table("video_project") {
    val videoAsset = varchar("video_asset", 36)
    val userId = long("user_id").index()
    val sessionId = varchar("session_id", 64).index()
    val plan = text("plan")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(videoAsset)
}

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

/** 주간 점검 (PROJECT.md 7.13, v14) */
object EvolutionRuns : LongIdTable("evolution_run") {
    val status = varchar("status", 16)
    val stage = varchar("stage", 64).nullable()
    val report = text("report").nullable()
    val error = text("error").nullable()
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
    /** 결과를 메신저로 알린 시각 */
    val notifiedAt = timestamp("notified_at").nullable()
}

/** 설정 변경안 (v14). changes·previous는 JSON 객체 */
object EvolutionProposals : LongIdTable("evolution_proposal") {
    val runId = long("run_id").nullable()
    val kind = varchar("kind", 16)
    val title = varchar("title", 200)
    val summary = text("summary")
    val changes = text("changes")
    val previous = text("previous")
    val status = varchar("status", 16).index()
    val createdAt = timestamp("created_at")
    val notifiedAt = timestamp("notified_at").nullable()
    val decidedAt = timestamp("decided_at").nullable()
}

/** 평가한 후보 모델 (v14) */
object EvolutionCandidates : org.jetbrains.exposed.v1.core.Table("evolution_candidate") {
    val name = varchar("name", 200)
    val digest = varchar("digest", 80)
    val result = varchar("result", 16)
    val score = double("score").nullable()
    val note = text("note").nullable()
    val runId = long("run_id").nullable()
    val checkedAt = timestamp("checked_at")
    override val primaryKey = PrimaryKey(name, digest)
}

/** 사용자별 설정 (U-07, v15) */
object UserSettingsTable : org.jetbrains.exposed.v1.core.Table("user_settings") {
    val userId = reference("user_id", Users.id, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val nickname = varchar("nickname", 40).nullable()
    val tone = varchar("tone", 16).default("DEFAULT")
    val voiceReply = bool("voice_reply").nullable()
    val voice = varchar("voice", 80).nullable()
    /** 자동 기억 (M27, v24). null이면 기본(GUEST만 끔) */
    val memoryAuto = bool("memory_auto").nullable()
    override val primaryKey = PrimaryKey(userId)
}

/** 사용자 정의 에이전트 (M22, v16). tools·collections는 줄바꿈으로 나눈 목록 */
object CustomAgents : LongIdTable("custom_agent") {
    val owner = reference("owner_user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val name = varchar("name", 40)
    val description = varchar("description", 400)
    val instructions = text("instructions")
    val model = varchar("model", 16)
    val tools = text("tools")
    val collections = text("collections")
    val updatedAt = timestamp("updated_at")

    init { uniqueIndex(owner, name) }
}

/** 팀 (M22, v17): 리더·멤버는 같은 사용자의 사용자 정의 에이전트 이름 */
object AgentTeams : LongIdTable("agent_team") {
    val owner = reference("owner_user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val name = varchar("name", 40)
    val description = varchar("description", 400)
    val leader = varchar("leader", 40)
    val members = text("members")
    val updatedAt = timestamp("updated_at")

    init { uniqueIndex(owner, name) }
}

/** A2A 에이전트 (M22, v18): 등록할 때 읽은 Agent Card 내용. 토큰은 키체인 */
object A2aAgents : LongIdTable("a2a_agent") {
    val owner = reference("owner_user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val name = varchar("name", 40)
    val cardUrl = varchar("card_url", 1000)
    val endpoint = varchar("endpoint", 1000)
    val protocol = varchar("protocol", 8)
    val displayName = varchar("display_name", 120)
    val description = text("description")
    val skills = text("skills")
    val streaming = bool("streaming")
    val auth = bool("auth")
    /** 사용자가 고른 내부 에이전트인가 (v19). 아니면 내부 주소에는 연결하지 않는다 */
    val local = bool("local").default(false)
    val addedAt = timestamp("added_at")

    init { uniqueIndex(owner, name) }
}

/** 웹에서 만든 스킬 (M22 K-01, v20) */
object Skills : LongIdTable("skill") {
    val owner = reference("owner_user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE)
    val name = varchar("name", 40)
    val description = varchar("description", 400)
    val agent = varchar("agent", 16)
    val tools = text("tools")
    val body = text("body")
    val updatedAt = timestamp("updated_at")

    init { uniqueIndex(owner, name) }
}

/** 학습 재료 (M25 L-25, v21). [dedupe]는 종류·라벨·질문을 정규화한 키 (같은 재료를 두 번 넣지 않게) */
object TrainingSamples : LongIdTable("training_sample") {
    val userId = reference("user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).index()
    val kind = varchar("kind", 16)
    val signal = varchar("signal", 16)
    val messageId = long("message_id").nullable()
    val question = text("question")
    val answer = text("answer").nullable()
    val label = varchar("label", 80)
    val confidence = double("confidence")
    val status = varchar("status", 16).index()
    val note = text("note").nullable()
    val dedupe = varchar("dedupe", 200).uniqueIndex()
    val createdAt = timestamp("created_at")
    val reviewedAt = timestamp("reviewed_at").nullable()
}

/** 큐레이터 진행 위치 등 작은 값 (M25, v21) */
/** 장기 기억 (M27, v24). 사용자별 사실 문장과 임베딩 */
object Memories : LongIdTable("memory") {
    val userId = reference("user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).index()
    val text = varchar("text", 400)
    val kind = varchar("kind", 16)
    val origin = varchar("source", 16)
    val vector = blob("vector").nullable()
    /** 벡터를 만든 임베딩 모델 (바뀌면 다시 만든다) */
    val model = varchar("model", 200).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val uses = integer("uses").default(0)
}

object CuratorState : org.jetbrains.exposed.v1.core.Table("curator_state") {
    val key = varchar("key", 40)
    val value = long("value")
    override val primaryKey = PrimaryKey(key)
}

/** 실행 (M22, v16) */
object AgentRuns : LongIdTable("agent_run") {
    val userId = reference("user_id", Users, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).index()
    val sessionId = varchar("session_id", 36).nullable()
    val agent = varchar("agent", 16)
    val customAgent = varchar("custom_agent", 40).nullable()
    val input = text("input")
    val status = varchar("status", 16)
    val answer = text("answer").nullable()
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
}

/** 실행의 단계 (M22, v16) */
object RunEvents : LongIdTable("run_event") {
    val run = reference("run_id", AgentRuns, onDelete = org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE).index()
    val seq = integer("seq")
    val at = timestamp("at")
    val kind = varchar("kind", 24)
    val title = varchar("title", 300)
    val detail = text("detail").nullable()
}
