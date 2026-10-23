package dev.vesta.storage

import dev.vesta.core.UserId
import dev.vesta.core.media.MediaAsset
import dev.vesta.core.media.MediaKind
import dev.vesta.core.media.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
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
    override suspend fun save(userId: UserId, kind: MediaKind, mime: String, bytes: ByteArray, caption: String?): MediaAsset = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = clock.instant()
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
                it[MediaAssets.caption] = caption
                it[createdAt] = now
            }
        }
        MediaAsset(id, userId, kind, mime, bytes.size.toLong(), caption, now)
    }

    override suspend fun list(userId: UserId, kind: MediaKind, limit: Int): List<MediaAsset> = withContext(Dispatchers.IO) {
        transaction(db) {
            MediaAssets.selectAll()
                .where { (MediaAssets.userId eq userId.value) and (MediaAssets.kind eq kind.name) }
                .orderBy(MediaAssets.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toAsset() }
        }
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
        "video/mp4" -> "mp4"
        else -> "bin"
    }
}

internal fun ResultRow.toAsset() = MediaAsset(
    this[MediaAssets.id],
    UserId(this[MediaAssets.userId]),
    MediaKind.valueOf(this[MediaAssets.kind]),
    this[MediaAssets.mime],
    this[MediaAssets.size],
    this[MediaAssets.caption],
    this[MediaAssets.createdAt],
)

/** 영상 프로젝트 (M24 G-05) */
class ExposedVideoProjectRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : dev.vesta.core.media.VideoProjectRepository {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override suspend fun save(userId: UserId, sessionId: String, project: dev.vesta.core.media.VideoProject) {
        withContext(Dispatchers.IO) {
            transaction(db) {
                VideoProjects.insert {
                    it[videoAsset] = project.videoAsset
                    it[VideoProjects.userId] = userId.value
                    it[VideoProjects.sessionId] = sessionId
                    it[plan] = json.encodeToString(dev.vesta.core.media.VideoProject.serializer(), project)
                    it[createdAt] = clock.instant()
                }
            }
        }
    }

    override suspend fun latest(userId: UserId, sessionId: String): dev.vesta.core.media.VideoProject? = withContext(Dispatchers.IO) {
        transaction(db) {
            VideoProjects.selectAll().where { (VideoProjects.userId eq userId.value) and (VideoProjects.sessionId eq sessionId) }
                .orderBy(VideoProjects.createdAt, SortOrder.DESC).limit(1).firstOrNull()
        }?.let { runCatching { json.decodeFromString(dev.vesta.core.media.VideoProject.serializer(), it[VideoProjects.plan]) }.getOrNull() }
    }
}
