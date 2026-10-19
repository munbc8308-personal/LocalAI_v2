package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import dev.vesta.app.BotTokens
import dev.vesta.channels.PairingCode
import dev.vesta.channels.telegram.HttpTelegramApi
import dev.vesta.core.ChannelType
import dev.vesta.core.config.VestaPaths
import dev.vesta.storage.ExposedChannelBindingStore
import dev.vesta.storage.VestaDatabase
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Telegram 봇 설정 (PROJECT.md 6.4~6.6, M10) */
class TelegramCommand : CliktCommand(name = "telegram") {
    override fun help(context: Context) = "Telegram 봇: 토큰 저장, 계정 연결"
    override fun run() = Unit
}

class TelegramTokenCommand : CliktCommand(name = "token") {
    override fun help(context: Context) = "BotFather에서 받은 봇 토큰을 키체인에 저장하고 서버를 다시 시작한다"
    private val token by option("--token", hidden = true).prompt("봇 토큰 (BotFather)", hideInput = true)

    override fun run() = runBlocking {
        val value = token.trim()
        if (!HttpTelegramApi.looksLikeToken(value)) {
            echo("토큰 형식이 아닙니다 (예: 123456789:AA...)", err = true)
            throw ProgramResult(1)
        }
        val me = runCatching { HttpTelegramApi(value).getMe() }.getOrElse {
            echo("Telegram이 토큰을 받지 않았습니다: ${it.message}", err = true)
            throw ProgramResult(1)
        }
        if (!BotTokens.write("telegram", value)) {
            echo("키체인에 저장하지 못했습니다", err = true)
            throw ProgramResult(1)
        }
        echo("@${me.username} 토큰을 키체인(dev.vesta.telegram)에 저장했습니다.")
        restartServer()
        echo("다음: vesta telegram pair 로 내 Telegram 계정을 연결하세요.")
    }
}

class TelegramOffCommand : CliktCommand(name = "off") {
    override fun help(context: Context) = "저장한 봇 토큰을 지워 봇을 끈다"

    override fun run() {
        echo(if (BotTokens.delete("telegram")) "토큰을 지웠습니다." else "저장된 토큰이 없습니다.")
        restartServer()
    }
}

class TelegramPairCommand : CliktCommand(name = "pair") {
    override fun help(context: Context) = "내 Telegram 계정을 연결할 일회용 코드를 만든다 (10분)"

    override fun run() = runBlocking {
        val db = VestaDatabase.open(VestaPaths.default().database).also { it.migrate() }
        val owner = db.ensureOwner(System.getProperty("user.name") ?: "owner")
        val code = PairingCode.generate()
        ExposedChannelBindingStore(db.db).createPairing(owner, ChannelType.TELEGRAM, PairingCode.hash(code), Duration.ofMinutes(10))
        val bot = BotTokens.read("telegram")?.let { runCatching { HttpTelegramApi(it).getMe().username }.getOrNull() }
        echo("10분 안에 Telegram에서 봇에게 보내세요:")
        echo("")
        echo("  /start $code")
        echo("")
        if (bot != null) echo("봇 열기: https://t.me/$bot")
        else echo("봇 토큰이 없거나 확인하지 못했습니다. 먼저 vesta telegram token")
        echo("코드는 한 번만 쓸 수 있고, 새로 만들면 이전 코드는 무효입니다.")
    }
}

class TelegramListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "연결된 Telegram 계정"

    override fun run() = runBlocking {
        val db = VestaDatabase.open(VestaPaths.default().database).also { it.migrate() }
        val list = ExposedChannelBindingStore(db.db).list(ChannelType.TELEGRAM)
        if (list.isEmpty()) echo("연결된 계정이 없습니다. vesta telegram pair")
        list.forEach { echo("${it.externalUserId}  사용자 ${it.user.userId.value} (${it.user.role})  세션 ${it.sessionId?.value ?: "-"}") }
    }
}

class TelegramUnlinkCommand : CliktCommand(name = "unlink") {
    override fun help(context: Context) = "Telegram 계정 연결을 끊는다"
    private val id by argument(help = "Telegram 사용자 ID (vesta telegram list)")

    override fun run() = runBlocking {
        val db = VestaDatabase.open(VestaPaths.default().database).also { it.migrate() }
        echo(if (ExposedChannelBindingStore(db.db).remove(ChannelType.TELEGRAM, id)) "연결을 끊었습니다: $id" else "없는 계정입니다: $id")
    }
}

/** launchd에 등록된 서버면 다시 띄워 새 토큰을 읽게 한다 */
private fun CliktCommand.restartServer() {
    val uid = runCatching { ProcessBuilder("/usr/bin/id", "-u").start().inputStream.bufferedReader().readText().trim() }.getOrNull()
    val ok = uid != null && runCatching {
        val process = ProcessBuilder("/bin/launchctl", "kickstart", "-k", "gui/$uid/dev.vesta.server").redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)
    echo(if (ok) "서버를 다시 시작했습니다." else "서버를 다시 시작하지 못했습니다. 서버를 직접 다시 시작하세요 (scripts/install-launchd.sh).")
}
