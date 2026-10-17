package dev.vesta.knowledge

import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.knowledge.Collection
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.storage.KnowledgeRepository
import dev.vesta.storage.NewChunk
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolute
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.streams.asSequence

private val log = KotlinLogging.logger {}

/** 색인 진행 상황. CLI·웹 UI가 표시한다. */
sealed interface IndexProgress {
    data class Indexed(val path: Path, val chunks: Int) : IndexProgress
    data class Unchanged(val path: Path) : IndexProgress
    data class Skipped(val path: Path, val reason: String) : IndexProgress
    data class Removed(val path: String) : IndexProgress
}

data class IndexSummary(val indexed: Int, val unchanged: Int, val skipped: Int, val removed: Int, val chunks: Int)

/**
 * 파일·폴더를 컬렉션에 색인한다 (PROJECT.md K-02~K-05, K-10).
 * 내용 해시가 같으면 건너뛴다. 폴더를 다시 색인하면 사라진 파일은 컬렉션에서 지운다.
 */
class Indexer(
    private val repository: KnowledgeRepository,
    private val inference: InferenceClient,
    private val chunker: Chunker = Chunker(),
    private val codeChunker: CodeChunker = CodeChunker(),
    private val batchSize: Int = 32,
    private val maxFileBytes: Long = 20L * 1024 * 1024,
) {
    suspend fun index(collection: Collection, targets: List<Path>, onProgress: (IndexProgress) -> Unit = {}): IndexSummary {
        var indexed = 0
        var unchanged = 0
        var skipped = 0
        var removed = 0
        var chunkCount = 0

        for (target in targets.map { it.absolute().normalize() }) {
            val files = if (collection.kind == CollectionKind.CODE) codeFiles(target) else files(target)
            for (file in files) {
                val result = runCatching { indexFile(collection, file) }.getOrElse {
                    log.warn(it) { "색인 실패: $file" }
                    IndexProgress.Skipped(file, it.message ?: it::class.simpleName.orEmpty())
                }
                when (result) {
                    is IndexProgress.Indexed -> { indexed++; chunkCount += result.chunks }
                    is IndexProgress.Unchanged -> unchanged++
                    is IndexProgress.Skipped -> skipped++
                    is IndexProgress.Removed -> Unit
                }
                onProgress(result)
            }
            if (target.isDirectory()) {
                val present = files.map { it.toString() }.toSet()
                repository.listDocuments(collection.id)
                    .filter { it.path.startsWith("$target/") && it.path !in present }
                    .forEach {
                        repository.removeDocument(collection.id, it.path)
                        removed++
                        onProgress(IndexProgress.Removed(it.path))
                    }
            }
        }
        return IndexSummary(indexed, unchanged, skipped, removed, chunkCount)
    }

    private suspend fun indexFile(collection: Collection, file: Path): IndexProgress {
        if (file.fileSize() > maxFileBytes) return IndexProgress.Skipped(file, "파일이 너무 큼 (${file.fileSize() / 1024 / 1024}MB)")
        val bytes = file.readBytes()
        val hash = sha256(bytes)
        if (repository.findDocument(collection.id, file.toString())?.contentHash == hash) return IndexProgress.Unchanged(file)

        val (parsed, chunks) = if (collection.kind == CollectionKind.CODE) {
            val relative = collection.rootPath?.let { runCatching { Path.of(it).relativize(file).toString() }.getOrNull() } ?: file.toString()
            ParsedDocument(relative, emptyList()) to codeChunker.chunk(relative, bytes.decodeToString())
        } else {
            DocumentParser.parse(file, bytes).let { it to chunker.chunk(it) }
        }
        if (chunks.isEmpty()) return IndexProgress.Skipped(file, "글자가 없음 (스캔 PDF는 아직 지원하지 않음)")

        val profile = EmbeddingProfile.of(collection.embeddingModel)
        val vectors = chunks.chunked(batchSize).flatMap { batch ->
            inference.embed(collection.embeddingModel, batch.map { profile.passage(parsed.title, it) })
        }
        repository.replaceDocument(
            collection = collection.id,
            path = file.toString(),
            title = parsed.title,
            contentHash = hash,
            chunks = chunks.zip(vectors) { chunk, vector -> NewChunk(chunk.text, chunk.location, chunk.heading, vector) },
        )
        return IndexProgress.Indexed(file, chunks.size)
    }

    private fun codeFiles(target: Path): List<Path> = when {
        target.isRegularFile() -> if (CodeFiles.supports(target)) listOf(target) else emptyList()
        target.isDirectory() -> CodeFiles.list(target)
        else -> emptyList()
    }

    private fun files(target: Path): List<Path> = when {
        target.isRegularFile() -> if (DocumentParser.supports(target)) listOf(target) else emptyList()
        target.isDirectory() -> Files.walk(target).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && DocumentParser.supports(it) }
                .filterNot { path -> target.relativize(path).any { it.name.startsWith(".") } || path.isHidden() }
                .sorted()
                .toList()
        }
        else -> emptyList()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * 임베딩 모델별 입력 형식. Qwen3-Embedding은 질의에 지시문을 붙여야 성능이 나온다.
 * 문서 쪽은 제목·구역을 앞에 붙여 맥락을 준다.
 */
data class EmbeddingProfile(val queryPrefix: String) {
    fun query(text: String) = queryPrefix + text
    fun passage(title: String, chunk: DocumentChunk) = buildString {
        append(title)
        chunk.heading?.let { append(" > ").append(it) }
        append("\n").append(chunk.text)
    }

    companion object {
        fun of(model: String): EmbeddingProfile = when {
            model.startsWith("qwen3-embedding") ->
                EmbeddingProfile("Instruct: Given a question, retrieve passages from the user's documents that answer it\nQuery: ")
            else -> EmbeddingProfile("")
        }
    }
}
