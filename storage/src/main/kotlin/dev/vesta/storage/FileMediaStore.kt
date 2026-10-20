package dev.vesta.storage

import dev.vesta.core.UserId
import dev.vesta.core.media.MediaAsset
import dev.vesta.core.media.MediaKind
import dev.vesta.core.media.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * 미디어 파일 저장 (M12). `<dir>/<사용자 ID>/<ID>.<확장자>`, 파일은 본인만 읽게(600) 만든다.
 * 조회는 사용자 범위로 제한한다: 다른 사용자의 ID를 알아도 읽을 수 없다.
 */
class FileMediaStore(private val db: Database, private val dir: Path, private val clock: Clock = Clock.systemUTC()) : MediaStore {
    override suspend fun save(userId: UserId, kind: MediaKind, mime: String, bytes: ByteArray): MediaAsset = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val folder = dir.resolve(userId.value.toString()).createDirectories()
        val file = folder.resolve("$id.${extension(mime)}")
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        file.writeBytes(bytes)
        transaction(db) {
            MediaAssets.insert {
                it[MediaAssets.id] = id
                it[MediaAssets.userId] = userId.value
                it[MediaAssets.kind] = kind.name
                it[MediaAssets.mime] = mime
                it[path] = file.toString()
                it[size] = bytes.size.toLong()
                it[createdAt] = clock.instant()
            }
        }
        MediaAsset(id, userId, kind, mime, bytes.size.toLong())
    }

    override suspend fun load(userId: UserId, id: String): Pair<MediaAsset, ByteArray>? = withContext(Dispatchers.IO) {
        val row = transaction(db) {
            MediaAssets.selectAll().where { (MediaAssets.id eq id) and (MediaAssets.userId eq userId.value) }.firstOrNull()
        } ?: return@withContext null
        val file = Path.of(row[MediaAssets.path])
        if (!file.exists()) return@withContext null
        row.toAsset() to file.readBytes()
    }

    private fun extension(mime: String) = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "ogg"
        else -> "bin"
    }
}

internal fun ResultRow.toAsset() = MediaAsset(
    this[MediaAssets.id],
    UserId(this[MediaAssets.userId]),
    MediaKind.valueOf(this[MediaAssets.kind]),
    this[MediaAssets.mime],
    this[MediaAssets.size],
)
