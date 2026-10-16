package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaPaths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) = "설정 파일 관리"
    override fun run() = Unit
}

class ConfigInitCommand : CliktCommand(name = "init") {
    override fun help(context: Context) = "기본 설정 파일(~/.vesta/config.yaml)을 만든다"
    private val force by option("--force", help = "이미 있으면 덮어쓴다").flag()

    override fun run() {
        val paths = VestaPaths.default()
        val file = paths.configFile
        if (file.exists() && !force) {
            echo("이미 있습니다: $file (덮어쓰려면 --force)", err = true)
            throw ProgramResult(1)
        }
        paths.home.createDirectories()
        file.writeText(ConfigLoader.defaultTemplate())
        echo("만들었습니다: $file")
    }
}

class ConfigShowCommand : CliktCommand(name = "show") {
    override fun help(context: Context) = "설정 파일을 검증하고 내용을 보여 준다"

    override fun run() {
        val file = VestaPaths.default().configFile
        if (!file.exists()) {
            echo("설정 파일 없음 → 기본값 사용 ($file). 만들려면: vesta config init")
            return
        }
        ConfigLoader.load(file) // 형식 오류면 여기서 예외
        echo("# $file (검증 통과)")
        echo(file.readText())
    }
}
