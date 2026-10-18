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
        SchemaUtils.create(SchemaVersion, Users, Sessions, Messages, Collections, Documents, Chunks, WebPages, WebSearchLogs, RoutingLogs, ToolRuns)
        // 키워드 검색(BM25)용 FTS5. 한국어는 접두어 검색으로 조사 차이를 흡수한다 (KnowledgeRepository.keywordSearch)
        exec("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, heading, tokenize = 'unicode61 remove_diacritics 2')")
        val current = SchemaVersion.selectAll().maxOfOrNull { it[SchemaVersion.version] } ?: 0
        // v6: 이미 있는 collections 테이블에 열 추가 (SchemaUtils.create는 기존 테이블을 바꾸지 않는다)
        val columns = exec("PRAGMA table_info(collections)") { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }.orEmpty()
        if ("kind" !in columns) exec("ALTER TABLE collections ADD COLUMN kind VARCHAR(16) DEFAULT 'DOCS' NOT NULL")
        if ("root_path" !in columns) exec("ALTER TABLE collections ADD COLUMN root_path TEXT NULL")
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
        /** 1: users, 2: sessions·messages (M1), 3: collections·documents·chunks·chunks_fts (M2), 4: web_pages·web_search_log (M3), 5: routing_log (M4), 6: collections.kind·root_path (M5), 7: tool_run (M6) */
        const val CURRENT_VERSION = 7

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
