package dev.vesta.cli

import dev.vesta.app.AppContainer
import dev.vesta.app.MacSystem

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.path
import dev.vesta.core.AgentType
import dev.vesta.core.decision.RoutingStage
import dev.vesta.router.Router
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path
import kotlin.io.path.readLines

class EvalCommand : CliktCommand(name = "eval") {
    override fun help(context: Context) = "품질 평가 (PROJECT.md S-04)"
    override fun run() = Unit
}

/** 라우팅 평가 (PROJECT.md M4 완료 기준: 한국어 평가셋 정확도 90% 이상). */
class EvalRoutingCommand : CliktCommand(name = "routing") {
    override fun help(context: Context) = "라우팅 평가셋으로 에이전트 선택(D1)·웹 필요(D2)·문서 필요(D3) 정확도를 잰다"
    private val file by option("--file", "-f", help = "평가셋 TSV").path(mustExist = true).default(Path("eval/routing-ko.tsv"))
    private val mode by option("--mode", help = "pipeline: 실제 라우터 / decision: 결정 모델만 / llm: 메인 모델만")
        .choice("pipeline", "decision", "llm").default("pipeline")
    private val threshold by option("--threshold", help = "결정 모델 신뢰도 임계값 (기본: 설정값)").double()
    private val model by option("--decision-model", help = "결정 모델 (기본: 설정값)")

    override fun run() = runBlocking {
        AppContainer().use { app ->
            val decisionConfig = app.config.decision.let { c ->
                c.copy(
                    model = model ?: c.model,
                    enabled = mode != "llm",
                    thresholds = c.thresholds.copy(agent = if (mode == "decision") 0.0 else threshold ?: c.thresholds.agent),
                )
            }
            val router = Router(
                decisionConfig,
                dev.vesta.decision.OllayaClient(decisionConfig.baseUrl, decisionConfig.model, app.httpForEval),
                if (mode == "decision") null else dev.vesta.router.LlmRouter(app.inference, app.modelManager),
            )
            val rows = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
            val all = AgentType.entries.toSet()
            router.route(rows.first()[0], emptyList(), all) // 모델 로드 시간 제외

            data class Row(val ok: Boolean, val stage: RoutingStage, val web: Boolean, val local: Boolean, val ms: Long, val miss: String?)
            val results = rows.map { (question, expected, web, local) ->
                val d = router.route(question, emptyList(), all)
                Row(
                    ok = d.agent.name == expected,
                    stage = d.stage,
                    web = ((d.needsWeb ?: 0.0) >= 0.5) == (web == "1"),
                    local = ((d.needsLocal ?: 0.0) >= 0.5) == (local == "1"),
                    ms = d.latencyMs,
                    miss = if (d.agent.name != expected) "$expected→${d.agent} ($question)" else null,
                ).also { if (it.miss != null) echo(dim("  ✗ ${it.miss}")) }
            }
            val n = results.size
            fun pct(x: Int) = "%d/%d (%.0f%%)".format(x, n, x * 100.0 / n)
            echo("D1 에이전트 ${pct(results.count { it.ok })}  ·  D2 웹 ${pct(results.count { it.web })}  ·  D3 문서 ${pct(results.count { it.local })}")
            results.groupBy { it.stage }.forEach { (stage, rs) ->
                echo("  ${stage.name.padEnd(15)} ${rs.size}건, 정답 ${rs.count { it.ok }}, 지연 중앙 ${rs.map { it.ms }.sorted()[rs.size / 2]}ms")
            }
            echo("지연 중앙 ${results.map { it.ms }.sorted()[n / 2]}ms · 결정 모델 ${decisionConfig.model} · 임계값 ${decisionConfig.thresholds.agent} · 모드 $mode")
        }
    }

    companion object {
        /** 설정 그대로의 라우터로 D1 정확도 (0~1). eval all에서 쓴다 */
        suspend fun score(app: AppContainer, file: java.nio.file.Path): Double {
            val rows = readTsv(file)
            val all = AgentType.entries.toSet()
            return rows.count { (question, expected) -> app.router.route(question, emptyList(), all).agent.name == expected }.toDouble() / rows.size
        }
    }
}
