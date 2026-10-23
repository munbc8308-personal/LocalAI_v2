package dev.vesta.cli

import dev.vesta.app.AppContainer
import dev.vesta.app.MacSystem

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.vesta.core.knowledge.Collection
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.knowledge.IndexProgress
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.time.TimeSource

/** 코드 저장소 관리 (PROJECT.md D-01). 저장소는 종류가 CODE인 컬렉션이다. */
class RepoCommand : CliktCommand(name = "repo") {
    override fun help(context: Context) = "코드 저장소 등록·갱신 (목록·삭제는 vesta collection list/remove)"
    override fun run() = Unit
}

class RepoAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "저장소를 등록하고 색인한다 (.gitignore를 따른다)"
    private val name by argument(help = "이름 (@이름으로 부른다)")
    private val path by argument(help = "저장소 루트").path(mustExist = true, canBeFile = false)
    private val description by option("--description", "-d", help = "무슨 저장소인지")

    override fun run() = runBlocking {
        AppContainer().use { app ->
            if (app.knowledge.findCollection(app.owner.userId, name) != null) {
                echo("이미 있습니다: $name (갱신: vesta repo update $name)", err = true)
                throw ProgramResult(1)
            }
            val model = app.embeddingModel()
            val root = path.absolute().normalize()
            val repo = app.knowledge.createCollection(app.owner.userId, name, description, model, CollectionKind.CODE, root.toString())
            indexRepo(app, repo)
        }
    }
}

class RepoUpdateCommand : CliktCommand(name = "update") {
    override fun help(context: Context) = "저장소를 다시 색인한다 (바뀐 파일만, 지워진 파일은 제거)"
    private val name by argument()

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val repo = app.knowledge.findCollection(app.owner.userId, name)?.takeIf { it.kind == CollectionKind.CODE }
                ?: run { echo("코드 저장소가 아닙니다: $name", err = true); throw ProgramResult(1) }
            indexRepo(app, repo)
        }
    }
}

private suspend fun CliktCommand.indexRepo(app: AppContainer, repo: Collection) {
    val start = TimeSource.Monotonic.markNow()
    var shown = 0
    val summary = app.indexer.index(repo, listOf(Path(repo.rootPath!!))) { progress ->
        when (progress) {
            is IndexProgress.Indexed -> if (shown++ < 15) echo(dim("  색인 ${progress.path.fileName} (${progress.chunks})"))
            is IndexProgress.Skipped -> echo(red("  건너뜀 ${progress.path}: ${progress.reason}"))
            is IndexProgress.Removed -> echo(dim("  삭제 ${progress.path}"))
            is IndexProgress.Unchanged -> Unit
        }
    }
    if (shown > 15) echo(dim("  … 외 ${shown - 15}개"))
    echo(
        "완료 ${"%.1f".format(start.elapsedNow().inWholeMilliseconds / 1000.0)}s — 색인 ${summary.indexed}, 그대로 ${summary.unchanged}, " +
            "건너뜀 ${summary.skipped}, 삭제 ${summary.removed}, 새 청크 ${summary.chunks}. 질문: @${repo.name} 질문",
    )
}
