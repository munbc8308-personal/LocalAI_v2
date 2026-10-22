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
