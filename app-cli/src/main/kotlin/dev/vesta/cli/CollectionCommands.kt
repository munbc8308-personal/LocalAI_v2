package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.vesta.core.knowledge.RetrieveOptions
import dev.vesta.knowledge.IndexProgress
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource

/** 문서 컬렉션 관리 (PROJECT.md K-01·K-02). */
class CollectionCommand : CliktCommand(name = "collection") {
    override fun help(context: Context) = "문서 컬렉션(지식 베이스) 관리"
    override fun run() = Unit
}

class CollectionCreateCommand : CliktCommand(name = "create") {
    override fun help(context: Context) = "컬렉션을 만든다"
    private val name by argument(help = "컬렉션 이름 (영문·숫자·-·_ 권장, @이름으로 부른다)")
    private val description by option("--description", "-d", help = "무슨 문서인지 설명 (라우팅에 쓴다)")
    private val embeddingModel by option("--embedding-model", help = "임베딩 모델 (기본: 설정의 models.embedding)")

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val model = embeddingModel ?: app.config.models.embedding?.name
                ?: run { echo("임베딩 모델이 없습니다. 설정의 models.embedding을 정하거나 --embedding-model을 주세요.", err = true); throw ProgramResult(1) }
            if (app.knowledge.findCollection(app.owner.userId, name) != null) {
                echo("이미 있습니다: $name", err = true)
                throw ProgramResult(1)
            }
            app.knowledge.createCollection(app.owner.userId, name, description, model)
            echo("만들었습니다: $name (임베딩 $model). 문서 추가: vesta collection add $name <파일 또는 폴더>")
        }
    }
}

class CollectionListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "컬렉션 목록"

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val collections = app.knowledge.listCollections(app.owner.userId)
            if (collections.isEmpty()) echo("컬렉션이 없습니다. 만들기: vesta collection create <이름>")
            collections.forEach {
                val stats = app.knowledge.stats(it.id)
                val kind = if (it.kind == dev.vesta.core.knowledge.CollectionKind.CODE) "코드 ${it.rootPath}" else "문서"
                echo("${it.name.padEnd(20)} [$kind] 파일 ${stats.documents}개 · 청크 ${stats.chunks}개 · ${it.embeddingModel}${it.description?.let { d -> " — $d" }.orEmpty()}")
            }
        }
    }
}

class CollectionAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "파일·폴더를 색인한다. 바뀐 파일만 다시 색인하고, 폴더에서 사라진 파일은 지운다"
    private val name by argument()
    private val paths by argument(help = "파일 또는 폴더 (.txt .md .pdf)").path(mustExist = true).multiple(required = true)

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val collection = app.knowledge.findCollection(app.owner.userId, name)
                ?: run { echo("컬렉션이 없습니다: $name", err = true); throw ProgramResult(1) }
            if (app.config.models.embedding != null) app.modelManager.acquire(dev.vesta.core.ModelRole.EMBEDDING)
            val start = TimeSource.Monotonic.markNow()
            val summary = app.indexer.index(collection, paths) { progress ->
                when (progress) {
                    is IndexProgress.Indexed -> echo("  색인 ${progress.path} (청크 ${progress.chunks})")
                    is IndexProgress.Unchanged -> echo(dim("  그대로 ${progress.path}"))
                    is IndexProgress.Skipped -> echo(red("  건너뜀 ${progress.path}: ${progress.reason}"))
                    is IndexProgress.Removed -> echo(dim("  삭제 ${progress.path}"))
                }
            }
            echo(
                "완료 ${"%.1f".format(start.elapsedNow().inWholeMilliseconds / 1000.0)}s — 색인 ${summary.indexed}, 그대로 ${summary.unchanged}, " +
                    "건너뜀 ${summary.skipped}, 삭제 ${summary.removed}, 새 청크 ${summary.chunks}",
            )
        }
    }
}

class CollectionRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: Context) = "컬렉션과 색인을 지운다 (원본 파일은 지우지 않음)"
    private val name by argument()
    private val yes by option("--yes", help = "확인 없이 지운다").flag()

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val collection = app.knowledge.findCollection(app.owner.userId, name)
                ?: run { echo("컬렉션이 없습니다: $name", err = true); throw ProgramResult(1) }
            if (!yes) {
                echo("컬렉션 '$name'의 색인을 지웁니다. 원본 파일은 그대로입니다. 계속하려면 --yes를 붙이세요.", err = true)
                throw ProgramResult(1)
            }
            app.knowledge.deleteCollection(app.owner.userId, collection.id)
            echo("지웠습니다: $name")
        }
    }
}

/** 검색 결과만 확인한다 (생성 없음). 검색 품질 점검용. */
class SearchCommand : CliktCommand(name = "search") {
    override fun help(context: Context) = "문서 검색 결과를 본다 (답변 생성 없이)"
    private val query by argument(help = "검색어")
    private val collections by option("--collection", "-c", help = "컬렉션 (생략 시 전체)").multiple()
    private val limit by option("--limit", "-n").int().default(5)

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val all = app.knowledge.listCollections(app.owner.userId)
            val targets = if (collections.isEmpty()) all else all.filter { it.name in collections }
            val start = TimeSource.Monotonic.markNow()
            val hits = app.retriever.retrieve(app.owner.userId, listOf(query), RetrieveOptions(targets.map { it.id }, limit))
            echo(dim("${hits.size}건 · ${start.elapsedNow().inWholeMilliseconds}ms · 리랭커 ${if (app.reranker != null) "사용" else "없음"}"))
            hits.forEachIndexed { i, hit ->
                echo("${i + 1}. ${hit.title}${hit.location?.let { " · $it" }.orEmpty()} ${dim("(%.4f)".format(hit.score))}")
                echo(dim("   " + hit.text.replace("\n", " ").take(160)))
            }
        }
    }
}
