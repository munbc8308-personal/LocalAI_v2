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
        SchemaUtils.create(SchemaVersion, Users, Sessions, Messages)
        val current = SchemaVersion.selectAll().maxOfOrNull { it[SchemaVersion.version] } ?: 0
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
        /** 1: users, 2: sessions·messages (M1) */
        const val CURRENT_VERSION = 2

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
