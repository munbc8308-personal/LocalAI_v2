package dev.vesta.knowledge

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

private val log = KotlinLogging.logger {}

/** 코드 저장소 파일 목록 (PROJECT.md D-01). git 저장소면 `.gitignore`를 따른다. */
object CodeFiles {
    val extensions = setOf(
        "kt", "kts", "java", "scala", "groovy", "py", "js", "jsx", "ts", "tsx", "mjs", "go", "rs", "swift", "rb", "php",
        // .md는 넣지 않는다: 설계 문서가 코드 검색 결과를 밀어낸다 (M5 평가). 문서는 문서 컬렉션으로 따로 등록한다
        "c", "h", "cc", "cpp", "hpp", "cs", "m", "sql", "sh", "zsh", "bash", "yaml", "yml", "toml", "gradle",
        "html", "css", "scss", "vue", "svelte", "proto", "graphql", "tf", "dockerfile",
    )
    private val ignoredDirs = setOf(".git", "build", "out", "target", "node_modules", ".gradle", ".idea", "dist", ".venv", "venv", "__pycache__", ".kotlin")

    fun supports(path: Path) = path.extension.lowercase() in extensions || path.name.equals("Dockerfile", ignoreCase = true)

    fun list(root: Path, maxBytes: Long = 512 * 1024): List<Path> =
        (gitFiles(root) ?: walk(root))
            .filter { it.isRegularFile() && supports(it) && Files.size(it) <= maxBytes }
            .sorted()

    /** 추적 중 파일 + 추적 안 됐지만 무시되지 않은 파일. git이 없거나 저장소가 아니면 null. */
    private fun gitFiles(root: Path): List<Path>? = runCatching {
        val process = ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z", "--cached", "--others", "--exclude-standard")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
        output.split('\u0000').filter { it.isNotBlank() }.map { root.resolve(it).normalize() }
    }.onFailure { log.debug(it) { "git ls-files 실패, 폴더를 직접 훑음: $root" } }.getOrNull()

    private fun walk(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.asSequence()
            .filterNot { path -> root.relativize(path).any { it.name in ignoredDirs || it.name.startsWith(".") } }
            .toList()
    }
}

/**
 * 선언(클래스·함수 등) 단위로 코드를 자른다 (PROJECT.md D-02).
 * tree-sitter 기반 정확한 구문 분할(D-07)은 P1 — 지금은 언어별 선언 줄 패턴으로 경계를 잡는다.
 * 작은 선언은 합치고, 큰 선언은 [maxLines] 줄씩 자른다. 위치는 "상대경로:시작–끝".
 */
class CodeChunker(
    private val targetChars: Int = 1_800,
    private val maxLines: Int = 120,
) {
    data class Piece(val text: String, val startLine: Int, val endLine: Int, val symbol: String?)

    fun chunk(relativePath: String, source: String): List<DocumentChunk> {
        val lines = source.lines()
        val boundaries = lines.indices.filter { isDeclaration(lines[it]) }.let { if (0 in it) it else listOf(0) + it }
        val raw = boundaries.mapIndexed { i, start ->
            val end = (boundaries.getOrNull(i + 1) ?: lines.size) - 1
            Piece(lines.subList(start, end + 1).joinToString("\n"), start + 1, end + 1, symbolOf(lines[start]))
        }.filter { it.text.isNotBlank() }

        val merged = mutableListOf<Piece>()
        for (piece in raw.flatMap { split(it) }) {
            val last = merged.lastOrNull()
            if (last != null && last.text.length + piece.text.length < targetChars && piece.endLine - last.startLine < maxLines) {
                merged[merged.lastIndex] = Piece(last.text + "\n" + piece.text, last.startLine, piece.endLine, last.symbol ?: piece.symbol)
            } else {
                merged += piece
            }
        }
        return merged.map { DocumentChunk(it.text, it.symbol, "$relativePath:${it.startLine}–${it.endLine}") }
    }

    private fun split(piece: Piece): List<Piece> {
        val lines = piece.text.lines()
        if (lines.size <= maxLines && piece.text.length <= targetChars * 2) return listOf(piece)
        return lines.chunked(maxLines).mapIndexed { i, part ->
            val start = piece.startLine + i * maxLines
            Piece(part.joinToString("\n"), start, start + part.size - 1, piece.symbol)
        }
    }

    private fun isDeclaration(line: String): Boolean {
        val indent = line.length - line.trimStart().length
        return indent <= 4 && DECLARATION.containsMatchIn(line)
    }

    private fun symbolOf(line: String): String? = SYMBOL.find(line)?.groupValues?.get(2)

    private companion object {
        private const val MODIFIERS = "(?:(?:public|private|protected|internal|open|abstract|sealed|data|inline|value|enum|annotation|" +
            "override|suspend|static|final|export|default|async|pub(?:\\(crate\\))?|unsafe|const)\\s+)*"
        private const val KEYWORDS = "class|interface|object|fun|def|function|func|fn|struct|trait|impl|enum|type|module|record"
        val DECLARATION = Regex("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*$MODIFIERS(?:$KEYWORDS)\\b")
        val SYMBOL = Regex("\\b($KEYWORDS)\\s+(?:<[^>]*>\\s*)?([A-Za-z_][\\w.]*)")
    }
}
