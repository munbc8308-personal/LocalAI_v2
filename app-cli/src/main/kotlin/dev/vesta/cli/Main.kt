package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class Vesta : CliktCommand(name = "vesta") {
    override fun help(context: Context) = "Vesta — Mac 로컬 AI 어시스턴트"
    override fun run() = Unit
}

fun main(args: Array<String>) = Vesta()
    .subcommands(
        ChatCommand(),
        CollectionCommand().subcommands(CollectionCreateCommand(), CollectionListCommand(), CollectionAddCommand(), CollectionRemoveCommand(), CollectionShareCommand(), CollectionUnshareCommand()),
        RepoCommand().subcommands(RepoAddCommand(), RepoUpdateCommand()),
        SearchCommand(),
        EvalCommand().subcommands(EvalRoutingCommand(), EvalRetrievalCommand(), EvalGroundingCommand(), EvalAllCommand()),
        StatusCommand(), PasswdCommand(), UserCommand.create(),
        *messengerCommands().toTypedArray(),
        TrainCommand.create(),
        ConfigCommand().subcommands(ConfigInitCommand(), ConfigShowCommand()), BenchCommand())
    .main(args)
