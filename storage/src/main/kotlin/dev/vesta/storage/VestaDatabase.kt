package dev.vesta.storage

import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import kotlin.io.path.createDirectories

private val log = KotlinLogging.logger {}

/** SQLite 파일 DB (`~/.vesta/vesta.db`). WAL 모드, 외래 키 켬. */
class VestaDatabase private constructor(val db: Database) {

    /** 스키마를 최신으로 맞춘다. 여러 번 불러도 안전하다. */
    fun migrate() = transaction(db) {
        SchemaUtils.create(SchemaVersion, Users, Sessions, Messages, Collections, CollectionShares, Documents, Chunks, WebPages, WebSearchLogs, RoutingLogs, ToolRuns, WebSessions, ChannelBindings, ChannelPairings, MediaAssets, MessageFeedbacks, TrainingRuns, ModelVersions, EvolutionRuns, EvolutionProposals, EvolutionCandidates, UserSettingsTable, CustomAgents, AgentTeams, A2aAgents, Skills, TrainingSamples, CuratorState, AgentRuns, RunEvents, VideoProjects, Memories, Schedules, ScheduleRuns, TaskPlans)
        // 키워드 검색(BM25)용 FTS5. 한국어는 접두어 검색으로 조사 차이를 흡수한다 (KnowledgeRepository.keywordSearch)
        exec("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, heading, tokenize = 'unicode61 remove_diacritics 2')")
        val current = SchemaVersion.selectAll().maxOfOrNull { it[SchemaVersion.version] } ?: 0
        // v6: 이미 있는 collections 테이블에 열 추가 (SchemaUtils.create는 기존 테이블을 바꾸지 않는다)
        val columns = exec("PRAGMA table_info(collections)") { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }.orEmpty()
        if ("kind" !in columns) exec("ALTER TABLE collections ADD COLUMN kind VARCHAR(16) DEFAULT 'DOCS' NOT NULL")
        if ("root_path" !in columns) exec("ALTER TABLE collections ADD COLUMN root_path TEXT NULL")
        // v11: media_asset.caption (생성 이미지 프롬프트)
        val mediaColumns = exec("PRAGMA table_info(media_asset)") { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }.orEmpty()
        if ("caption" !in mediaColumns) exec("ALTER TABLE media_asset ADD COLUMN caption TEXT NULL")
        // v19: a2a_agent.local (사용자가 고른 내부 에이전트인지, M22 보안 검토)
        val a2aColumns = exec("PRAGMA table_info(a2a_agent)") { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }.orEmpty()
        if ("local" !in a2aColumns) exec("ALTER TABLE a2a_agent ADD COLUMN local BOOLEAN DEFAULT 0 NOT NULL")
        // v23: users.email (지인 공개의 Access 로그인 이메일, M26)
        val userColumns = exec("PRAGMA table_info(users)") { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }.orEmpty()
        if ("email" !in userColumns) exec("ALTER TABLE users ADD COLUMN email VARCHAR(254) NULL")
        // v24: user_settings.memory_auto (자동 기억 켜기·끄기, M27). memory 표는 위 create가 만든다
        val settingColumns = exec("PRAGMA table_info(user_settings)") { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }.orEmpty()
        if ("memory_auto" !in settingColumns) exec("ALTER TABLE user_settings ADD COLUMN memory_auto BOOLEAN NULL")
        // v25: skill.status·origin·run_id (스킬 초안, M28), user_settings.skill_auto
        if ("skill_auto" !in settingColumns) exec("ALTER TABLE user_settings ADD COLUMN skill_auto BOOLEAN NULL")
        val skillColumns = exec("PRAGMA table_info(skill)") { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }.orEmpty()
        if ("status" !in skillColumns) exec("ALTER TABLE skill ADD COLUMN status VARCHAR(8) DEFAULT 'ACTIVE' NOT NULL")
        if ("origin" !in skillColumns) exec("ALTER TABLE skill ADD COLUMN origin VARCHAR(8) DEFAULT 'MANUAL' NOT NULL")
        if ("run_id" !in skillColumns) exec("ALTER TABLE skill ADD COLUMN run_id BIGINT NULL")
        // v27: messages_fts (지난 대화 검색, M30). trigram이라 조사가 붙어도 부분이 맞으면 찾고, LIKE도 색인을 쓴다.
        // messages를 원본으로 삼고(글을 두 번 저장하지 않음) 트리거로 따라간다. 지운 대화는 CASCADE 삭제 때 트리거가 빼 준다
        val hadMessagesFts = exec("SELECT name FROM sqlite_master WHERE name = 'messages_fts'") { rs -> rs.next() } == true
        exec("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(content, content = 'messages', content_rowid = 'id', tokenize = 'trigram')")
        exec("CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content); END")
        exec("CREATE TRIGGER IF NOT EXISTS messages_fts_ad AFTER DELETE ON messages BEGIN INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.id, old.content); END")
        exec("CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE OF content ON messages BEGIN INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.id, old.content); INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content); END")
        if (!hadMessagesFts) exec("INSERT INTO messages_fts(messages_fts) VALUES ('rebuild')")
        if (current < CURRENT_VERSION) {
            SchemaVersion.insert { it[version] = CURRENT_VERSION }
            log.info { "schema $current -> $CURRENT_VERSION" }
        }
    }

    /** 단일 사용자 동안 쓰는 OWNER 계정. 없으면 만든다. */
    fun ensureOwner(name: String): UserId = transaction(db) {
        val existing = Users.selectAll().where { Users.role eq UserRole.OWNER }.firstOrNull()
        val id = existing?.get(Users.id)?.value ?: Users.insertAndGetId {
            it[Users.name] = name
            it[role] = UserRole.OWNER
        }.value
        UserId(id)
    }

    fun schemaVersion(): Int = transaction(db) {
        SchemaVersion.selectAll().maxOfOrNull { it[SchemaVersion.version] } ?: 0
    }

    companion object {
        /** 1: users, 2: sessions·messages (M1), 3: collections·documents·chunks·chunks_fts (M2), 4: web_pages·web_search_log (M3), 5: routing_log (M4), 6: collections.kind·root_path (M5), 7: tool_run (M6), 8: web_session (M9), 9: channel_binding·channel_pairing (M10), 10: media_asset (M12), 11: media_asset.caption (M13), 12: message_feedback·training_run·model_version (M14), 13: collection_share (M15), 14: evolution_run·evolution_proposal·evolution_candidate (M16), 15: user_settings (M20), 16: custom_agent·agent_run·run_event (M22), 17: agent_team (M22 팀), 18: a2a_agent (M22 A2A), 19: a2a_agent.local, 20: skill (M22 스킬), 21: training_sample·curator_state (M25), 22: video_project (M24), 23: users.email (M26), 24: memory·user_settings.memory_auto (M27), 25: skill.status·origin·run_id·user_settings.skill_auto (M28), 26: schedule·schedule_run (M29), 27: messages_fts·트리거 (M30), 28: task_plan (M32) */
        const val CURRENT_VERSION = 28

        fun open(file: Path): VestaDatabase {
            file.parent?.createDirectories()
            val config = SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                enforceForeignKeys(true)
                setBusyTimeout(5_000)
            }
            val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:$file" }
            return VestaDatabase(Database.connect(dataSource))
        }
    }
}
