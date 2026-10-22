package dev.vesta.cli

import dev.vesta.app.AppContainer
import dev.vesta.app.MacSystem

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.knowledge.RetrieveOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/** 평가셋 TSV: `질문<TAB>정답(| 구분)…`, `#` 줄은 주석 */
internal fun readTsv(file: Path): List<List<String>> =
    file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

@Serializable
data class RetrievalScore(val collection: String, val questions: Int, val hit1: Int, val hit5: Int, val mrr: Double)

@Serializable
data class GroundingScore(
    val collection: String,
    val questions: Int,
    /** 검증한 주장 문장 수 */
    val claims: Int,
    /** 근거로 확인되지 않은 문장 수 */
    val unsupported: Int,
    /** 출처 번호 없이 답한 질문 수 ("찾지 못했다" 제외) */
    val uncited: Int,
)

/** 검색 적중률 (PROJECT.md 12장 품질 목표: 정답 근거가 상위 5개 안 85% 이상) */
internal suspend fun evalRetrieval(app: AppContainer, collection: String, file: Path, echo: (String) -> Unit): RetrievalScore {
    val target = app.knowledge.findCollection(app.owner.userId, collection) ?: error("컬렉션이 없습니다: $collection")
    var hit1 = 0
    var hit5 = 0
    var rr = 0.0
    val rows = readTsv(file)
    for ((question, expected) in rows) {
        val hits = app.retriever.retrieve(app.owner.userId, listOf(question), RetrieveOptions(listOf(target.id), 5))
        val labels = hits.map { "${it.title} · ${it.location}" }
        val rank = labels.indexOfFirst { label -> expected.split('|').any { it in label } }.takeIf { it >= 0 }?.plus(1)
        if (rank == 1) hit1++
        if (rank != null) { hit5++; rr += 1.0 / rank } else echo(dim("  ✗ $question"))
    }
    return RetrievalScore(collection, rows.size, hit1, hit5, rr / rows.size)
}

/** 근거율 (12장: 출처 없는 사실 주장 최소화). 실제 DocsAgent로 답하고 D7 판정을 모은다 */
internal suspend fun evalGrounding(app: AppContainer, collection: String, file: Path, limit: Int, cleanup: Boolean = false, echo: (String) -> Unit): GroundingScore {
    var claims = 0
    var unsupported = 0
    var uncited = 0
    val rows = readTsv(file).take(limit)
    for ((question) in rows) {
        val events = app.conversation.send(app.owner, ChannelType.CLI, null, UserInput("@$collection $question")).toList()
        // 주간 점검은 매주 돌므로 평가용 대화를 대화 목록에 남기지 않는다
        if (cleanup) events.filterIsInstance<AgentEvent.SessionReady>().forEach { app.sessions.delete(app.owner.userId, it.sessionId) }
        val answer = events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }
        events.filterIsInstance<AgentEvent.Grounding>().firstOrNull()?.let { g ->
            claims += g.checked
            unsupported += g.unsupported.size
            g.unsupported.forEach { echo(dim("  ⚠ ${it.take(90)}  ← $question")) }
        }
        if (Citations.numbers(answer).isEmpty() && "찾지 못" !in answer) uncited++
    }
    return GroundingScore(collection, rows.size, claims, unsupported, uncited)
}

class EvalRetrievalCommand : CliktCommand(name = "retrieval") {
    override fun help(context: Context) = "검색 평가셋으로 hit@1·hit@5·MRR을 잰다"
    private val collection by option("--collection", "-c").required()
    private val file by option("--file", "-f").path(mustExist = true).required()

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val s = evalRetrieval(app, collection, file, ::echo)
            echo("$collection: hit@1 ${s.hit1}/${s.questions} · hit@5 ${s.hit5}/${s.questions} (%.0f%%) · MRR %.2f".format(s.hit5 * 100.0 / s.questions, s.mrr))
        }
    }
}

class EvalGroundingCommand : CliktCommand(name = "grounding") {
    override fun help(context: Context) = "문서 질의 답변의 근거율을 잰다 (D7 근거 검증 결과 집계)"
    private val collection by option("--collection", "-c").required()
    private val file by option("--file", "-f").path(mustExist = true).required()
    private val limit by option("--limit", "-n").int().default(15)

    override fun run() = runBlocking {
        AppContainer().use { app ->
            if (app.judge == null) { echo("결정 모델이 꺼져 있어 근거 검증을 할 수 없습니다", err = true); throw ProgramResult(1) }
            val s = evalGrounding(app, collection, file, limit, echo = ::echo)
            echo("$collection: 주장 ${s.claims}문장 중 근거 미확인 ${s.unsupported} (%.0f%%) · 출처 없이 답한 질문 ${s.uncited}/${s.questions}".format(s.unsupported * 100.0 / maxOf(1, s.claims)))
        }
    }
}

/** 12장 품질 목표 전체 점검 (S-04). 결과는 ~/.vesta/eval/ 에 JSON으로 남긴다 */
class EvalAllCommand : CliktCommand(name = "all") {
    override fun help(context: Context) = "라우팅·문서 검색·코드 검색·근거율을 한 번에 재고 품질 목표와 비교한다"
    private val docs by option("--docs", help = "문서 컬렉션 (eval/retrieval-project-md.tsv)").default("b")
    private val code by option("--code", help = "코드 저장소 (eval/code-vesta.tsv)").default("vesta")

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val lines = mutableListOf<String>()
            fun row(name: String, value: Double, target: Double, higherIsBetter: Boolean = true, unit: String = "%") {
                val ok = if (higherIsBetter) value >= target else value <= target
                lines += "${if (ok) "✓" else "✗"} ${name.padEnd(28)} ${"%.0f".format(value)}$unit (목표 ${if (higherIsBetter) "≥" else "≤"} ${"%.0f".format(target)}$unit)"
            }
            echo("라우팅 평가…")
            val routing = EvalRoutingCommand.score(app, Path("eval/routing-ko.tsv"))
            row("라우팅 정확도 (D1)", routing * 100, 90.0)

            if (app.knowledge.findCollection(app.owner.userId, docs) != null && Path("eval/retrieval-project-md.tsv").exists()) {
                echo("문서 검색 평가…")
                val r = evalRetrieval(app, docs, Path("eval/retrieval-project-md.tsv"), ::echo)
                row("문서 검색 hit@5 ($docs)", r.hit5 * 100.0 / r.questions, 85.0)
                echo("근거율 평가 (답변 생성, 몇 분 걸림)…")
                val g = evalGrounding(app, docs, Path("eval/retrieval-project-md.tsv"), 15, echo = ::echo)
                row("근거 미확인 문장 비율", g.unsupported * 100.0 / maxOf(1, g.claims), 15.0, higherIsBetter = false)
            } else lines += "- 문서 컬렉션 '$docs' 없음 → 문서 평가 건너뜀"

            if (app.knowledge.findCollection(app.owner.userId, code) != null && Path("eval/code-vesta.tsv").exists()) {
                echo("코드 검색 평가…")
                val c = evalRetrieval(app, code, Path("eval/code-vesta.tsv"), ::echo)
                row("코드 검색 hit@5 ($code)", c.hit5 * 100.0 / c.questions, 85.0)
            } else lines += "- 코드 저장소 '$code' 없음 → 코드 평가 건너뜀"

            echo("\n품질 목표 (PROJECT.md 12장)")
            lines.forEach(::echo)
            val dir = app.paths.home.resolve("eval").createDirectories()
            val file = dir.resolve("eval-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt")
            file.writeText(lines.joinToString("\n"))
            echo(dim("저장: $file"))
        }
    }
}

internal val EvalJson = Json { prettyPrint = true }
