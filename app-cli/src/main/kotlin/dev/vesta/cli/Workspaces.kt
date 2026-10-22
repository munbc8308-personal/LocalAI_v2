package dev.vesta.cli

import dev.vesta.app.AppContainer
import dev.vesta.core.ModelRole
import dev.vesta.core.agent.Workspace
import dev.vesta.core.knowledge.CollectionKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * CLI 작업 폴더 (PROJECT.md 7.14, M17). `vesta`를 실행한 폴더를 작업 폴더로, 그 폴더(와 저장소 루트까지의 상위 폴더)의
 * `VESTA.md`를 프로젝트 지침으로 쓴다. 홈 폴더 자체나 홈 밖에서 실행하면 작업 폴더 없이 동작한다.
 */
internal object Workspaces {
    const val FILE = "VESTA.md"

    fun current(cwd: Path = Path(System.getProperty("user.dir")), home: Path = Path(System.getProperty("user.home"))): Workspace? {
        val dir = runCatching { cwd.toRealPath() }.getOrNull() ?: return null
        if (!Workspace.usable(dir.toString(), home.toString())) return null
        return Workspace(dir.toString(), instructions(dir, runCatching { home.toRealPath() }.getOrDefault(home)))
    }

    /** 저장소 루트(.git이 있는 폴더)에서 작업 폴더까지 차례로 읽어 붙인다: 위쪽이 공통 규칙, 아래쪽이 더 구체적인 규칙 */
    fun instructions(dir: Path, home: Path): String? {
        val chain = generateSequence(dir) { it.parent }.takeWhile { it.startsWith(home) && it != home }.toList()
        val top = chain.indexOfFirst { it.resolve(".git").exists() }.let { if (it < 0) 0 else it }
        val files = chain.take(top + 1).reversed().map { it.resolve(FILE) }.filter { it.isRegularFile() }
        if (files.isEmpty()) return null
        val text = files.joinToString("\n\n") { file ->
            val body = runCatching { file.readText() }.getOrDefault("").trim()
            if (files.size == 1) body else "## ${dir.relativize(file.parent).toString().ifEmpty { "." }}/$FILE\n$body"
        }
        return text.take(Workspace.MAX_INSTRUCTIONS).ifBlank { null }
    }

    /**
     * VESTA.md 신뢰 (M17 보안 검토): 받은 저장소의 지침 파일이 모델에게 위험한 지시를 할 수 있어,
     * 처음 보거나 내용이 바뀌면 사용자가 한 번 승인해야 쓴다. 승인한 내용의 SHA-256을 폴더별로 `~/.vesta/trusted-instructions`에 적는다
     */
    fun trusted(workspace: Workspace, store: Path = trustFile()): Boolean {
        val text = workspace.instructions ?: return true
        return runCatching { Files.readAllLines(store) }.getOrDefault(emptyList()).any { it == "${sha256(text)}\t${workspace.path}" }
    }

    fun trust(workspace: Workspace, store: Path = trustFile()) {
        val text = workspace.instructions ?: return
        val kept = runCatching { Files.readAllLines(store) }.getOrDefault(emptyList()).filterNot { it.endsWith("\t${workspace.path}") }
        Files.createDirectories(store.parent)
        Files.write(store, kept + "${sha256(text)}\t${workspace.path}")
    }

    /**
     * 화면에 그대로 보이지 않는 문자가 들어 있는가: 제어 문자(줄바꿈·탭 제외), 서식 문자(양방향 제어·폭 없는 문자·태그 블록 U+E0000~),
     * 사용자 정의·미할당·서로게이트, 줄·문단 구분자. 들어 있으면 승인 화면과 모델이 받는 내용이 달라 보일 수 있어 신뢰하지 않는다.
     * UTF-16 단위가 아니라 코드 포인트로 본다
     */
    fun hasControlChars(text: String): Boolean = marks(text).any { it }

    /** 보이지 않는 문자를 `\u{…}`로 */
    fun visible(text: String): String = buildString {
        val points = text.codePoints().toArray()
        val flags = marks(text)
        points.forEachIndexed { i, cp -> if (flags[i]) append("\\u{%x}".format(cp)) else appendCodePoint(cp) }
    }

    /** 코드 포인트마다 숨은 문자인가. 기호 뒤의 표시 선택자(U+FE0E·FE0F, "❤️")는 정상 이모지 표기라 허용한다 */
    private fun marks(text: String): List<Boolean> {
        val points = text.codePoints().toArray()
        return points.mapIndexed { i, cp ->
            if ((cp == 0xFE0E || cp == 0xFE0F) && i > 0 && Character.getType(points[i - 1]).toByte() == Character.OTHER_SYMBOL) false else hidden(cp)
        }
    }

    /** 기본 무시 문자(Default_Ignorable)·보이지 않는 채움 문자: 결합 무시, 한글 채움, 몽골 변형 선택자, 점자 빈칸, 변형 선택자 */
    private val IGNORABLE = listOf(
        0x034F..0x034F, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F, 0x2800..0x2800, 0x3164..0x3164,
        0xFE00..0xFE0F, 0xFFA0..0xFFA0, 0xE0100..0xE01EF,
    )

    private fun hidden(cp: Int): Boolean {
        if (cp == '\n'.code || cp == '\t'.code) return false
        if (IGNORABLE.any { cp in it }) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> cp in 0xE0000..0xE007F
        }
    }

    /** 승인 전에 보여 줄 지침. 모델이 받는 내용 전부를 보여 준다 (뒤쪽에 숨긴 지시를 못 보고 승인하지 않게). 길면 줄 수를 알린다 */
    fun preview(workspace: Workspace, lines: Int = 80): String {
        val all = visible(workspace.instructions.orEmpty()).lines()
        return all.take(lines).joinToString("\n") + if (all.size > lines) "\n… (${all.size}줄 중 ${lines}줄만 표시 — 나머지도 모델에 들어갑니다. 파일을 직접 확인하세요)" else ""
    }

    private fun trustFile(): Path = dev.vesta.core.config.VestaPaths.default().home.resolve("trusted-instructions")

    private fun sha256(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 표시용: 홈은 `~` */
    fun display(path: String) = path.replaceFirst(System.getProperty("user.home"), "~")

    /**
     * 작업 폴더가 등록한 코드 저장소 안이면 바뀐 파일만 다시 색인한다 (D-01, M17). 결과 한 줄을 돌려준다.
     * 저장소가 아닌 git 폴더면 등록 방법을 알려 준다. 둘 다 아니면 null
     */
    suspend fun refreshRepo(workspace: Workspace): String? = AppContainer().use { app ->
        val dir = Path.of(workspace.path)
        val repo = app.knowledge.listCollections(app.owner.userId)
            .firstOrNull { it.kind == CollectionKind.CODE && it.rootPath?.let { root -> dir.startsWith(Path.of(root)) } == true }
        if (repo == null) {
            val gitRoot = generateSequence(dir) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) } ?: return@use null
            return@use "이 폴더는 등록한 저장소가 아닙니다. 코드로 찾아 답하게 하려면: vesta repo add ${gitRoot.fileName} ${display(gitRoot.toString())}"
        }
        if (app.config.models.embedding != null) app.modelManager.acquire(ModelRole.EMBEDDING)
        val summary = app.indexer.index(repo, listOf(Path(repo.rootPath!!)))
        if (summary.indexed == 0 && summary.removed == 0) "저장소 ${repo.name}: 바뀐 파일 없음"
        else "저장소 ${repo.name}: 바뀐 파일 ${summary.indexed}개 다시 색인, 지운 파일 ${summary.removed}개"
    }
}
