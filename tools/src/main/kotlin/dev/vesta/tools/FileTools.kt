package dev.vesta.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.tools.ApprovalDetails
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import dev.vesta.knowledge.DocumentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.streams.asSequence

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
internal fun Tool.fail(message: String) = ToolResult("오류: $message", isError = true)

/** 사람이 읽는 크기 */
internal fun size(bytes: Long) = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

private val skipDirs = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".venv")

class CurrentTimeTool(private val zone: ZoneId = ZoneId.systemDefault()) : Tool {
    override val spec = ToolSpec("current_time", "현재 날짜·시각과 시간대를 돌려준다.", schema())
    override val risk = RiskLevel.READ
    override suspend fun execute(arguments: JsonObject, context: ToolContext) =
        ToolResult(ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd (E) HH:mm:ss VV")))
}

class ListFilesTool(private val sandbox: PathSandbox) : Tool {
    override val readsLocalData = true
    override val spec = ToolSpec(
        "list_files",
        "폴더 안의 파일·폴더 목록을 크기·수정 시각과 함께 보여 준다. recursive=true면 하위 폴더까지 (최대 300개).",
        schema(
            "path" to prop("string", "폴더 경로. ~ 사용 가능"),
            "recursive" to prop("boolean", "하위 폴더 포함 여부"),
            "pattern" to prop("string", "파일 이름 glob 필터. 예: *.pdf"),
            required = listOf("path"),
        ),
    )
    override val risk = RiskLevel.READ

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val dir = when (val c = sandbox.check(arguments.string("path") ?: return@withContext fail("path가 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        if (!dir.isDirectory()) return@withContext fail("폴더가 아님: ${sandbox.display(dir)}")
        val matcher = arguments.string("pattern")?.let { dir.fileSystem.getPathMatcher("glob:$it") }
        val stream = if (arguments.bool("recursive") == true) Files.walk(dir, 8) else Files.list(dir)
        val entries = stream.use { s ->
            s.asSequence()
                .filter { it != dir && dir.relativize(it).none { part -> part.name in skipDirs || part.name.startsWith(".") } }
                .filter { matcher == null || it.isDirectory() || matcher.matches(it.fileName) }
                .take(301)
                .toList()
        }
        val lines = entries.take(300).map { path ->
            val relative = dir.relativize(path)
            if (path.isDirectory()) "$relative/" else "$relative  ${size(path.fileSize())}  ${path.getLastModifiedTime().toInstant().toString().take(16)}"
        }
        val more = if (entries.size > 300) "\n… 300개까지만 표시" else ""
        ToolResult("${sandbox.display(dir)} (${lines.size}개)\n" + lines.joinToString("\n") + more, summary = "${sandbox.display(dir)}: ${lines.size}개")
    }
}

class ReadFileTool(private val sandbox: PathSandbox, private val maxChars: Int) : Tool {
    override val readsLocalData = true
    override val spec = ToolSpec(
        "read_file",
        "파일 내용을 읽는다. 텍스트·Markdown·PDF 지원. 긴 파일은 start_line/end_line으로 일부만 읽는다.",
        schema(
            "path" to prop("string", "파일 경로"),
            "start_line" to prop("integer", "시작 줄 (1부터)"),
            "end_line" to prop("integer", "끝 줄"),
            required = listOf("path"),
        ),
    )
    override val risk = RiskLevel.READ

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val file = when (val c = sandbox.check(arguments.string("path") ?: return@withContext fail("path가 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        if (!file.isRegularFile()) return@withContext fail("파일이 아님: ${sandbox.display(file)}")
        if (file.fileSize() > 20L * 1024 * 1024) return@withContext fail("파일이 너무 큼 (${size(file.fileSize())})")
        val text = if (DocumentParser.supports(file) && file.name.endsWith(".pdf", ignoreCase = true)) {
            DocumentParser.parse(file).blocks.joinToString("\n\n") { it.text }
        } else {
            val bytes = file.readBytes()
            if (bytes.take(4096).any { it == 0.toByte() }) return@withContext fail("바이너리 파일은 읽지 않음")
            bytes.decodeToString()
        }
        val lines = text.lines()
        val start = (arguments.int("start_line") ?: 1).coerceIn(1, maxOf(1, lines.size))
        val end = (arguments.int("end_line") ?: lines.size).coerceIn(start, maxOf(start, lines.size))
        var body = lines.subList(start - 1, end).joinToString("\n")
        val truncated = body.length > maxChars
        if (truncated) body = body.take(maxChars)
        val header = "${sandbox.display(file)} ($start–$end / ${lines.size}줄)"
        ToolResult("$header\n$body" + if (truncated) "\n… (${maxChars}자에서 잘림. start_line으로 나머지를 읽을 것)" else "", summary = header)
    }
}

class SearchFilesTool(private val sandbox: PathSandbox) : Tool {
    override val readsLocalData = true
    override val spec = ToolSpec(
        "search_files",
        "폴더 아래에서 이름(glob) 또는 내용(포함 문자열)으로 파일을 찾는다. 최대 50개.",
        schema(
            "path" to prop("string", "찾을 폴더"),
            "name" to prop("string", "파일 이름 glob. 예: *회의*.md"),
            "contains" to prop("string", "내용에 들어 있는 글자 (텍스트 파일만, 대소문자 무시)"),
            required = listOf("path"),
        ),
    )
    override val risk = RiskLevel.READ

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val dir = when (val c = sandbox.check(arguments.string("path") ?: return@withContext fail("path가 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        val matcher = arguments.string("name")?.let { dir.fileSystem.getPathMatcher("glob:$it") }
        val needle = arguments.string("contains")?.lowercase()
        if (matcher == null && needle == null) return@withContext fail("name 또는 contains가 필요함")
        val hits = Files.walk(dir, 10).use { s ->
            s.asSequence()
                .filter { it.isRegularFile() && dir.relativize(it).none { part -> part.name in skipDirs || part.name.startsWith(".") } }
                .filter { matcher == null || matcher.matches(it.fileName) }
                .filter { path ->
                    needle == null || (path.fileSize() < 2 * 1024 * 1024 && runCatching { path.readBytes() }.getOrNull()
                        ?.takeIf { bytes -> bytes.take(4096).none { it == 0.toByte() } }
                        ?.decodeToString()?.lowercase()?.contains(needle) == true)
                }
                .take(50)
                .toList()
        }
        ToolResult(
            if (hits.isEmpty()) "찾은 파일 없음" else hits.joinToString("\n") { "${sandbox.display(it)}  ${size(it.fileSize())}" },
            summary = "찾은 파일 ${hits.size}개",
        )
    }
}

class WriteFileTool(private val sandbox: PathSandbox) : Tool {
    override val spec = ToolSpec(
        "write_file",
        "파일에 쓴다. mode: create(새 파일, 있으면 실패) / overwrite(덮어쓰기) / append(끝에 붙이기). 사용자 승인이 필요하다.",
        schema(
            "path" to prop("string", "파일 경로"),
            "content" to prop("string", "쓸 내용"),
            "mode" to prop("string", "create / overwrite / append (기본 create)"),
            required = listOf("path", "content"),
        ),
    )
    override val risk = RiskLevel.WRITE

    override fun describe(arguments: JsonObject): ApprovalDetails {
        val path = arguments.string("path").orEmpty()
        val content = arguments.string("content").orEmpty()
        val mode = arguments.string("mode") ?: "create"
        val target = (sandbox.check(path) as? PathSandbox.Check.Ok)?.path
        val exists = target?.exists() == true
        val action = when (mode) { "overwrite" -> "덮어쓰기"; "append" -> "끝에 붙이기"; else -> "새 파일" }
        return ApprovalDetails(
            title = "파일 쓰기 ($action): ${target?.let(sandbox::display) ?: path} · ${size(content.toByteArray().size.toLong())}",
            body = content.take(2_000) + if (content.length > 2_000) "\n… (${content.length}자 중 2,000자만 표시)" else "",
            warning = if (exists && mode == "overwrite") "기존 파일을 덮어씁니다" else null,
            targets = listOfNotNull(target?.toString()),
        )
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val file = when (val c = sandbox.check(arguments.string("path") ?: return@withContext fail("path가 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        val content = arguments.string("content") ?: ""
        when (arguments.string("mode") ?: "create") {
            "create" -> if (file.exists()) return@withContext fail("이미 있는 파일: ${sandbox.display(file)} (덮어쓰려면 mode=overwrite)")
            "overwrite", "append" -> Unit
            else -> return@withContext fail("mode는 create / overwrite / append")
        }
        file.parent?.createDirectories()
        if (arguments.string("mode") == "append") Files.writeString(file, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        else file.writeText(content)
        ToolResult("저장함: ${sandbox.display(file)} (${size(file.fileSize())})")
    }
}

class MakeDirectoryTool(private val sandbox: PathSandbox) : Tool {
    override val spec = ToolSpec("make_directory", "폴더를 만든다 (중간 폴더 포함). 사용자 승인이 필요하다.", schema("path" to prop("string", "폴더 경로"), required = listOf("path")))
    override val risk = RiskLevel.WRITE
    override fun describe(arguments: JsonObject) = ApprovalDetails(
        "폴더 만들기: ${arguments.string("path")}", arguments.string("path").orEmpty(),
        targets = listOfNotNull((sandbox.check(arguments.string("path").orEmpty()) as? PathSandbox.Check.Ok)?.path?.toString()),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val dir = when (val c = sandbox.check(arguments.string("path") ?: return@withContext fail("path가 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        dir.createDirectories()
        ToolResult("만듦: ${sandbox.display(dir)}")
    }
}

class MoveFileTool(private val sandbox: PathSandbox) : Tool {
    override val spec = ToolSpec(
        "move_file",
        "파일·폴더를 옮기거나 이름을 바꾼다. 대상이 있으면 실패. 사용자 승인이 필요하다.",
        schema("from" to prop("string", "원래 경로"), "to" to prop("string", "새 경로"), required = listOf("from", "to")),
    )
    override val risk = RiskLevel.WRITE
    override fun describe(arguments: JsonObject): ApprovalDetails {
        val paths = listOf("from", "to").map { (sandbox.check(arguments.string(it).orEmpty()) as? PathSandbox.Check.Ok)?.path?.toString() }
        // 한쪽이라도 허용 폴더 밖이면 대상을 비운다 (자동 승인하지 않게)
        return ApprovalDetails("옮기기: ${arguments.string("from")} → ${arguments.string("to")}", "", targets = if (null in paths) emptyList() else paths.filterNotNull())
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val from = when (val c = sandbox.check(arguments.string("from") ?: return@withContext fail("from이 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        val to = when (val c = sandbox.check(arguments.string("to") ?: return@withContext fail("to가 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        if (!from.exists()) return@withContext fail("없음: ${sandbox.display(from)}")
        if (to.exists()) return@withContext fail("대상이 이미 있음: ${sandbox.display(to)}")
        to.parent?.createDirectories()
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        ToolResult("옮김: ${sandbox.display(from)} → ${sandbox.display(to)}")
    }
}

internal fun Path.isInside(root: Path) = startsWith(root)
