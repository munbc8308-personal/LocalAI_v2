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
        val realHome = runCatching { home.toRealPath() }.getOrDefault(home)
        return Workspace(dir.toString(), instructions(dir, realHome), skills(dir, realHome).first)
    }

    /**
     * 작업 폴더의 스킬 (SK-01): 저장소 루트에서 작업 폴더까지의 `.vesta/skills/<이름>/SKILL.md` (아래쪽이 같은 이름을 덮는다).
     * 형식이 틀리거나 숨은 문자가 있는 파일은 쓰지 않고 그 폴더 이름을 돌려준다
     */
    fun skills(dir: Path, home: Path): Pair<List<dev.vesta.core.skill.Skill>, List<String>> {
        val chain = generateSequence(dir) { it.parent }.takeWhile { it.startsWith(home) && it != home }.toList()
        val top = chain.indexOfFirst { it.resolve(".git").exists() }.let { if (it < 0) 0 else it }
        val skipped = mutableListOf<String>()
        val found = chain.take(top + 1).reversed().flatMap { level ->
            val root = level.resolve(".vesta").resolve("skills")
            if (!Files.isDirectory(root)) return@flatMap emptyList()
            Files.list(root).use { it.toList() }.sorted().mapNotNull { folder ->
                val file = folder.resolve("SKILL.md")
                if (!file.isRegularFile()) return@mapNotNull null
                val text = runCatching { if (Files.size(file) > 64_000) null else file.readText() }.getOrNull()
                text?.let { dev.vesta.core.skill.Skill.parse(folder.fileName.toString(), it, dev.vesta.core.skill.SkillSource.PROJECT) }
                    ?: null.also { skipped += folder.fileName.toString() }
            }
        }
        return found.reversed().distinctBy { it.name }.reversed().take(100) to skipped
    }

    /** 쓰지 않은 스킬 폴더 (형식 오류·숨은 문자) */
    fun skippedSkills(workspace: Workspace, home: Path = Path(System.getProperty("user.home"))): List<String> =
        skills(Path.of(workspace.path), runCatching { home.toRealPath() }.getOrDefault(home)).second

    /** 승인(해시)할 내용: VESTA.md + 스킬 전부. 스킬 하나만 바뀌어도 다시 묻는다 */
    private fun trustText(workspace: Workspace): String? =
        listOfNotNull(workspace.instructions, workspace.skills.takeIf { it.isNotEmpty() }?.joinToString("\n\u0000\n") { it.toMarkdown() })
            .takeIf { it.isNotEmpty() }?.joinToString("\n\u0000skills\n")

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
        val text = trustText(workspace) ?: return true
        return runCatching { Files.readAllLines(store) }.getOrDefault(emptyList()).any { it == "${sha256(text)}\t${workspace.path}" }
    }

    fun trust(workspace: Workspace, store: Path = trustFile()) {
        val text = trustText(workspace) ?: return
        val kept = runCatching { Files.readAllLines(store) }.getOrDefault(emptyList()).filterNot { it.endsWith("\t${workspace.path}") }
        Files.createDirectories(store.parent)
        Files.write(store, kept + "${sha256(text)}\t${workspace.path}")
    }

    /** 화면에 그대로 보이지 않는 문자가 들어 있는가 (`HiddenChars`, 승인 화면과 모델이 받는 내용이 달라 보이지 않게) */
    fun hasControlChars(text: String): Boolean = dev.vesta.core.text.HiddenChars.has(text)

    /** 보이지 않는 문자를 `\u{…}`로 */
    fun visible(text: String): String = dev.vesta.core.text.HiddenChars.visible(text)

    /** 승인 전에 보여 줄 지침. 모델이 받는 내용 전부를 보여 준다 (뒤쪽에 숨긴 지시를 못 보고 승인하지 않게). 길면 줄 수를 알린다 */
    fun preview(workspace: Workspace, lines: Int = 80): String {
        val skills = workspace.skills.joinToString("\n\n") { "### 스킬 /${it.name} (.vesta/skills)\n" + it.toMarkdown() }
        val all = visible(listOfNotNull(workspace.instructions, skills.ifEmpty { null }).joinToString("\n\n")).lines()
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
