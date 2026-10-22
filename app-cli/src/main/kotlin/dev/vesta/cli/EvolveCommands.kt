package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import dev.vesta.app.AppContainer
import dev.vesta.evolution.EvolutionService
import dev.vesta.evolution.ProposalOutcome
import kotlinx.coroutines.runBlocking

/** 주간 자가 개선 (PROJECT.md 7.13, M16) */
class EvolveCommand : CliktCommand(name = "evolve") {
    override fun help(context: Context) = "주간 자가 개선: 새 모델 평가·임계값 조정·결정 모델 재학습, 제안 승인·되돌리기"
    override fun run() = Unit

    companion object {
        fun create() = EvolveCommand().subcommands(EvolveRunCommand(), EvolveListCommand(), EvolveApproveCommand(), EvolveRejectCommand(), EvolveRollbackCommand())
    }
}

class EvolveRunCommand : CliktCommand(name = "run") {
    override fun help(context: Context) = "주간 점검을 지금 실행한다 (launchd가 일요일 새벽에 실행). 후보를 받으면 한 시간 넘게 걸릴 수 있다"
    private val candidates by option("--candidate", "-c", help = "모델 목록을 읽지 않고 이 모델만 평가 (여러 번 지정 가능)").multiple()
    private val noPull by option("--no-pull", help = "받아 두지 않은 후보는 건너뛴다").flag()
    private val skipModels by option("--skip-models", help = "모델 평가를 건너뛴다").flag()
    private val skipTune by option("--skip-tune", help = "임계값 조정을 건너뛴다").flag()
    private val skipRetrain by option("--skip-retrain", help = "결정 모델 재학습 확인을 건너뛴다").flag()
    private val dryRun by option("--dry-run", help = "평가만 하고 받기·제안·반영·재학습·모델 지우기를 하지 않는다").flag()
    private val scheduled by option("--scheduled", help = "launchd 실행: 설정에서 꺼져 있으면(evolution.enabled: false) 아무것도 하지 않는다").flag()

    override fun run() = runBlocking {
        if (scheduled && !AppContainer().use { it.config.evolution.enabled }) {
            echo("주간 점검이 꺼져 있습니다 (evolution.enabled: false)")
            return@runBlocking
        }
        val options = WeeklyOptions(candidates, !noPull, !skipModels, !skipTune, !skipRetrain, dryRun)
        val report = WeeklyCheck(options) { echo(it) }.run()
        echo("\n" + report.lines().filter { it.isNotBlank() }.size.let { "끝 (보고서 ${it}줄, vesta evolve list로 다시 보기)" })
    }
}

class EvolveListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "최근 점검과 제안"
    override fun run() = runBlocking {
        AppContainer().use { app ->
            val runs = app.evolutionRepository.runs(3)
            if (runs.isEmpty()) echo("아직 점검한 적이 없습니다 (vesta evolve run)")
            runs.firstOrNull()?.let { run ->
                echo("마지막 점검 #${run.id} ${run.startedAt} · ${run.status}${run.stage?.let { " · $it" }.orEmpty()}${run.error?.let { " · 오류: $it" }.orEmpty()}")
                run.report?.let { echo(it.trim()) }
            }
            val proposals = app.evolutionRepository.proposals(10)
            if (proposals.isNotEmpty()) {
                echo("\n제안")
                proposals.forEach { echo("  #${it.id} ${it.createdAt.toString().take(16)} ${it.title} — ${EvolutionService.label(it.status)}") }
            }
            val tuned = app.evolution.current()
            echo("\n지금 덮어쓴 설정 (${app.paths.tunedFile}): " + if (tuned.isEmpty()) "없음" else tuned.entries.joinToString { "${it.key}=${it.value}" })
        }
    }
}

private fun CliktCommand.decide(block: suspend (AppContainer) -> ProposalOutcome) = runBlocking {
    AppContainer().use { app ->
        val outcome = block(app)
        outcome.proposal?.let { echo(EvolutionService.describe(it)) }
        echo(outcome.message, err = !outcome.ok)
        if (!outcome.ok) throw ProgramResult(1)
    }
}

class EvolveApproveCommand : CliktCommand(name = "approve") {
    override fun help(context: Context) = "제안을 승인해 반영한다 (서버는 하던 답변이 끝나면 다시 시작)"
    private val id by argument().long()
    override fun run() = decide { it.evolution.approve(id) }
}

class EvolveRejectCommand : CliktCommand(name = "reject") {
    override fun help(context: Context) = "제안을 거절한다"
    private val id by argument().long()
    override fun run() = decide { it.evolution.reject(id) }
}

class EvolveRollbackCommand : CliktCommand(name = "rollback") {
    override fun help(context: Context) = "반영한 변경을 되돌린다 (번호를 빼면 가장 최근 것)"
    private val id by argument().long().optional()
    override fun run() = decide { app ->
        val target = id ?: app.evolution.lastApplied()?.id ?: return@decide ProposalOutcome(false, "되돌릴 변경이 없습니다")
        app.evolution.rollback(target)
    }
}
