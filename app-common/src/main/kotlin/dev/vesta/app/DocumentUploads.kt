package dev.vesta.app

import dev.vesta.core.UserContext
import dev.vesta.core.knowledge.Collection
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.identity.RolePolicy
import dev.vesta.storage.KnowledgeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * 웹에서 문서 컬렉션 만들기·올리기 (M36 F-04). 팀 검증에서 바깥의 가족은 문서를 올릴 길이 없었다(안내가 Mac 터미널 명령).
 * 올린 파일은 `<memberRoot>/<이름>/collections/<컬렉션>/`에 두고, 그 폴더를 백그라운드로 색인한다(폴더에서 지운 파일은 색인에서도 빠진다).
 * 본인 컬렉션만 다루고, 손님은 쓰지 못한다(로컬 문서를 쓰지 않는 역할)
 */
class DocumentUploads(
    private val knowledge: KnowledgeRepository,
    private val index: suspend (Collection, Path) -> Unit,
    private val embeddingModel: suspend () -> String,
    private val userName: suspend (UserContext) -> String?,
    private val root: Path,
    private val scope: CoroutineScope,
) {
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    /** 컬렉션을 만든다. 문제가 있으면 그 이유 */
    suspend fun create(user: UserContext, name: String, description: String?): String? {
        refuse(user)?.let { return it }
        if (!NAME.matches(name)) return "이름은 글자·숫자로 시작하는 1~40자(글자·숫자·_-)여야 합니다 (@이름으로 부릅니다)"
        if (knowledge.findCollection(user.userId, name) != null) return "이미 있는 이름입니다: $name"
        if (knowledge.listCollections(user.userId).size >= MAX_COLLECTIONS) return "컬렉션은 ${MAX_COLLECTIONS}개까지 만들 수 있습니다"
        knowledge.createCollection(user.userId, name, description?.trim()?.take(300)?.ifEmpty { null }, embeddingModel())
        return null
    }

    /** 문서를 올린다(같은 이름이면 바꾼다). 색인은 백그라운드. 문제가 있으면 그 이유 */
    suspend fun upload(user: UserContext, collectionName: String, fileName: String, bytes: ByteArray): String? {
        val (collection, folder) = target(user, collectionName) ?: return "컬렉션이 없습니다: $collectionName"
        val file = safeName(fileName) ?: return "파일 이름은 .txt .md .pdf 로 끝나는 보통 이름이어야 합니다"
        if (bytes.isEmpty()) return "빈 파일입니다"
        if ((Files.list(folder).use { it.count() }) >= MAX_DOCUMENTS && !Files.exists(folder.resolve(file))) return "한 컬렉션에 문서는 ${MAX_DOCUMENTS}개까지입니다"
        val tmp = Files.createTempFile(folder, ".upload-", ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, folder.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        reindex(collection, folder)
        return null
    }

    /** 올린 문서 이름들. 컬렉션이 없거나 손님이면 null */
    suspend fun documents(user: UserContext, collectionName: String): List<String>? {
        if (refuse(user) != null) return null
        val (_, folder) = target(user, collectionName) ?: return null
        return Files.list(folder).use { s -> s.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !it.fileName.toString().startsWith(".") }.map { it.fileName.toString() }.sorted().toList() }
    }

    /** 문서 하나를 지운다(색인에서도). 문제가 있으면 그 이유 */
    suspend fun deleteDocument(user: UserContext, collectionName: String, fileName: String): String? {
        val (collection, folder) = target(user, collectionName) ?: return "컬렉션이 없습니다: $collectionName"
        val file = safeName(fileName) ?: return "없는 문서입니다"
        val target = folder.resolve(file)
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "없는 문서입니다"
        Files.delete(target)
        reindex(collection, folder)
        return null
    }

    /** 컬렉션과 올린 파일을 지운다 */
    suspend fun deleteCollection(user: UserContext, collectionName: String): Boolean {
        if (refuse(user) != null) return false
        val collection = knowledge.findCollection(user.userId, collectionName)?.takeIf { it.kind == CollectionKind.DOCS } ?: return false
        // 링크를 따라가지 않고 지운다 (Files.walk는 기본으로 링크를 따라가지 않는다). 폴더가 밖을 가리키면 파일은 건드리지 않는다
        folderOf(user, collectionName)?.takeIf { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && contained(it) }
            ?.let { dir -> Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
        return knowledge.deleteCollection(user.userId, collection.id)
    }

    private fun refuse(user: UserContext): String? = if (RolePolicy.usesLocalData(user.role)) null else "손님 계정은 문서를 올릴 수 없습니다"

    private suspend fun target(user: UserContext, name: String): Pair<Collection, Path>? {
        if (refuse(user) != null) return null
        val collection = knowledge.findCollection(user.userId, name)?.takeIf { it.kind == CollectionKind.DOCS } ?: return null
        val folder = folderOf(user, name) ?: return null
        Files.createDirectories(folder)
        // 경로 중간이나 폴더가 심볼릭 링크면 밖(남의 폴더)을 가리킬 수 있다: 실제 경로가 root 안이고 링크가 없을 때만 (보안 검토)
        if (!contained(folder)) return null
        return collection to folder
    }

    /** `<root>/<사용자 이름>/collections/<컬렉션>`. 밖으로 나가는 이름이면 null */
    private suspend fun folderOf(user: UserContext, name: String): Path? {
        val owner = userName(user) ?: return null
        val folder = root.resolve(owner).resolve("collections").resolve(name).normalize()
        return folder.takeIf { NAME.matches(name) && it.startsWith(root) }
    }

    /** [folder]가 root 아래 실제 폴더이고, root부터 그 사이에 심볼릭 링크가 없는지 */
    private fun contained(folder: Path): Boolean {
        val realRoot = root.toRealPath()
        if (!folder.toRealPath().startsWith(realRoot)) return false
        var p = folder
        while (p.startsWith(root) && p != root) {
            if (Files.isSymbolicLink(p)) return false
            p = p.parent ?: break
        }
        return true
    }

    /** 같은 컬렉션의 색인은 차례로 (올리기가 몰려도 겹치지 않게). 올린 폴더에는 보통 파일만 있어야 한다: 링크·하위 폴더가 있으면 색인하지 않는다 (보안 검토) */
    private fun reindex(collection: Collection, folder: Path) {
        scope.launch {
            locks.getOrPut(collection.id.value) { Mutex() }.withLock {
                val odd = Files.list(folder).use { s -> s.filter { Files.isSymbolicLink(it) || !Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList() }
                if (odd.isNotEmpty() || !contained(folder)) {
                    log.warn { "웹에서 올린 문서 폴더에 링크·폴더가 있어 색인하지 않음: ${collection.name} ${odd.map { it.fileName }}" }
                    return@withLock
                }
                runCatching { index(collection, folder) }.onFailure { log.warn(it) { "웹에서 올린 문서 색인 실패: ${collection.name}" } }
            }
        }
    }

    companion object {
        val NAME = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,39}$")
        const val MAX_COLLECTIONS = 20
        const val MAX_DOCUMENTS = 200
        const val MAX_MEGABYTES = 20
        private val EXTENSIONS = setOf("txt", "md", "pdf")

        /** 경로 없이 보통 파일 이름만 (숨은 문자·점으로 시작·경로 구분자 거부) */
        fun safeName(name: String): String? {
            val base = name.trim()
            if (base.isEmpty() || base.length > 120 || base.startsWith(".") || base.any { it == '/' || it == '\\' || it == ':' || it.code < 0x20 } || dev.vesta.core.text.HiddenChars.has(base)) return null
            if (base.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return null
            return base
        }
    }
}
