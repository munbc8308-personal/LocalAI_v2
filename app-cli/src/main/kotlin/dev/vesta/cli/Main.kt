package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking

/**
 * `vesta` 하나만 치면 대화(chat), `vesta -p "질문"`이면 한 번만 묻고 끝낸다 (M17). 나머지는 하위 명령
 */
class Vesta : CliktCommand(name = "vesta") {
    override fun help(context: Context) = "Vesta — Mac 로컬 AI 어시스턴트. 하위 명령 없이 실행하면 대화를 시작한다"
    override val invokeWithoutSubcommand = true

    private val print by option("--print", "-p", help = "한 번만 묻고 답을 출력한 뒤 끝낸다 (지금 폴더가 작업 폴더)")
    private val acceptWrites by option("--accept-writes", help = "파일 쓰기·수정은 묻지 않고 승인 (셸은 계속 묻는다)").flag()
    private val local by option("--local", help = "서버가 떠 있어도 이 프로세스에서 직접 실행").flag()
    private val noWorkspace by option("--no-workspace", help = "지금 폴더를 작업 폴더로 쓰지 않는다").flag()

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val question = print
        if (question != null) {
            val code = runBlocking { PrintMode.run(question, acceptWrites, local, !noWorkspace) }
            if (code != 0) throw ProgramResult(code)
            return
        }
        ChatCommand().parse(buildList {
            if (acceptWrites) add("--accept-writes")
            if (local) add("--local")
            if (noWorkspace) add("--no-workspace")
        })
    }
}

fun main(args: Array<String>) = Vesta()
    .subcommands(
        ChatCommand(),
        CollectionCommand().subcommands(CollectionCreateCommand(), CollectionListCommand(), CollectionAddCommand(), CollectionRemoveCommand(), CollectionShareCommand(), CollectionUnshareCommand()),
        RepoCommand().subcommands(RepoAddCommand(), RepoUpdateCommand()),
        SearchCommand(),
        EvalCommand().subcommands(EvalRoutingCommand(), EvalRetrievalCommand(), EvalGroundingCommand(), EvalAllCommand()),
        StatusCommand(), DoctorCommand(), SetupCommand(), PasswdCommand(), UserCommand.create(),
        *messengerCommands().toTypedArray(),
        TrainCommand.create(), EvolveCommand.create(),
        ConfigCommand().subcommands(ConfigInitCommand(), ConfigShowCommand()), BenchCommand(), ModelsCommand.create(), ExportCommand(), ImportCommand(), UpdateCommand(), UninstallCommand(), PublicCommand.create(), MemoryCommand.create())
    .main(args)
