package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import dev.vesta.app.BotTokens
import dev.vesta.channels.PairingCode
import dev.vesta.channels.discord.HttpDiscordApi
import dev.vesta.channels.slack.HttpSlackApi
import dev.vesta.channels.telegram.HttpTelegramApi
import dev.vesta.core.ChannelType
import dev.vesta.core.config.VestaPaths
import dev.vesta.storage.ExposedChannelBindingStore
import dev.vesta.storage.VestaDatabase
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 메신저 봇 설정 (PROJECT.md 6.4~6.6, M10·M11): 토큰 저장, 계정 연결 */
class MessengerCommand(name: String, private val title: String) : CliktCommand(name = name) {
    override fun help(context: Context) = "$title 봇: 토큰 저장, 계정 연결"
    override fun run() = Unit
}

fun messengerCommands(): List<CliktCommand> = listOf(
    MessengerCommand("telegram", "Telegram").subcommands(
        TelegramTokenCommand(),
        PairCommand(ChannelType.TELEGRAM, "telegram") { code ->
            echo("10분 안에 Telegram에서 봇에게 보내세요:")
            echo("")
            echo("  /start $code")
            echo("")
            val bot = BotTokens.read("telegram")?.let { runCatching { HttpTelegramApi(it).getMe().username }.getOrNull() }
            if (bot != null) echo("봇 열기: https://t.me/$bot") else echo("봇 토큰이 없거나 확인하지 못했습니다. 먼저 vesta telegram token")
        },
        ListCommand(ChannelType.TELEGRAM, "telegram"),
        UnlinkCommand(ChannelType.TELEGRAM, "telegram"),
        OffCommand("telegram", BotTokens.BOT),
    ),
    MessengerCommand("discord", "Discord").subcommands(
        DiscordTokenCommand(),
        PairCommand(ChannelType.DISCORD, "discord") { code ->
            echo("10분 안에 Discord에서 Vesta 봇에게 DM으로 보내세요:")
            echo("")
            echo("  $code")
            echo("")
            echo("봇과 같은 서버에 있어야 DM을 보낼 수 있습니다 (서버 멤버 목록에서 봇을 눌러 메시지).")
        },
        ListCommand(ChannelType.DISCORD, "discord"),
        UnlinkCommand(ChannelType.DISCORD, "discord"),
        OffCommand("discord", BotTokens.BOT),
    ),
    MessengerCommand("slack", "Slack").subcommands(
        SlackManifestCommand(),
        SlackTokenCommand(),
        PairCommand(ChannelType.SLACK, "slack") { code ->
            echo("10분 안에 Slack에서 Vesta 앱의 메시지 탭에 보내세요:")
            echo("")
            echo("  $code")
        },
        ListCommand(ChannelType.SLACK, "slack"),
        UnlinkCommand(ChannelType.SLACK, "slack"),
        OffCommand("slack", BotTokens.BOT, BotTokens.APP),
    ),
)

class TelegramTokenCommand : CliktCommand(name = "token") {
    override fun help(context: Context) = "BotFather에서 받은 봇 토큰을 키체인에 저장하고 서버를 다시 시작한다"
    private val token by option("--token", hidden = true).prompt("봇 토큰 (BotFather)", hideInput = true)

    override fun run() = runBlocking {
        val value = token.trim()
        if (!HttpTelegramApi.looksLikeToken(value)) fail("토큰 형식이 아닙니다 (예: 123456789:AA...)")
        val me = runCatching { HttpTelegramApi(value).getMe() }.getOrElse { fail("Telegram이 토큰을 받지 않았습니다: ${it.message}") }
        if (!BotTokens.write("telegram", value)) fail("키체인에 저장하지 못했습니다")
        echo("@${me.username} 토큰을 키체인(dev.vesta.telegram)에 저장했습니다.")
        restartServer()
        echo("다음: vesta telegram pair 로 내 Telegram 계정을 연결하세요.")
    }
}

class DiscordTokenCommand : CliktCommand(name = "token") {
    override fun help(context: Context) = "Developer Portal의 Bot 토큰을 키체인에 저장하고 서버를 다시 시작한다"
    private val token by option("--token", hidden = true).prompt("Bot 토큰 (Developer Portal → Bot → Reset Token)", hideInput = true)

    override fun run() = runBlocking {
        val value = token.trim()
        if (!HttpDiscordApi.looksLikeToken(value)) fail("토큰 형식이 아닙니다 (점으로 나뉜 세 부분)")
        val api = HttpDiscordApi(value)
        val me = runCatching { api.me() }.getOrElse { fail("Discord가 토큰을 받지 않았습니다: ${it.message}") }
        if (!BotTokens.write("discord", value)) fail("키체인에 저장하지 못했습니다")
        echo("${me.username} 토큰을 키체인(dev.vesta.discord)에 저장했습니다.")
        runCatching { api.applicationId() }.onSuccess {
            echo("봇을 내 서버에 넣으세요 (권한 없음, DM만 씁니다): ${HttpDiscordApi.inviteUrl(it)}")
        }
        restartServer()
        echo("다음: vesta discord pair 로 내 Discord 계정을 연결하세요.")
    }
}

class SlackTokenCommand : CliktCommand(name = "token") {
    override fun help(context: Context) = "Slack 앱의 봇 토큰(xoxb-)과 앱 토큰(xapp-)을 키체인에 저장하고 서버를 다시 시작한다"
    private val botToken by option("--bot-token", hidden = true).prompt("봇 토큰 (OAuth & Permissions → Bot User OAuth Token, xoxb-)", hideInput = true)
    private val appToken by option("--app-token", hidden = true).prompt("앱 토큰 (Basic Information → App-Level Tokens, xapp-)", hideInput = true)

    override fun run() = runBlocking {
        val bot = botToken.trim()
        val app = appToken.trim()
        if (!HttpSlackApi.looksLikeBotToken(bot)) fail("봇 토큰 형식이 아닙니다 (xoxb-로 시작)")
        if (!HttpSlackApi.looksLikeAppToken(app)) fail("앱 토큰 형식이 아닙니다 (xapp-로 시작, connections:write 권한)")
        val api = HttpSlackApi(bot, app)
        val me = runCatching { api.authTest() }.getOrElse { fail("Slack이 봇 토큰을 받지 않았습니다: ${it.message}") }
        runCatching { api.openConnection() }.getOrElse { fail("Socket Mode 연결을 열지 못했습니다 (Socket Mode를 켰는지, 앱 토큰 권한 connections:write): ${it.message}") }
        if (!BotTokens.write("slack", bot) || !BotTokens.write("slack", app, BotTokens.APP)) fail("키체인에 저장하지 못했습니다")
        echo("${me.user} (${me.team}) 토큰을 키체인(dev.vesta.slack)에 저장했습니다.")
        restartServer()
        echo("다음: vesta slack pair 로 내 Slack 계정을 연결하세요.")
    }
}

/** Slack 앱 만들기용 설정 (api.slack.com/apps → Create New App → From a manifest) */
class SlackManifestCommand : CliktCommand(name = "manifest") {
    override fun help(context: Context) = "Slack 앱을 만들 때 붙여 넣을 manifest를 보여 준다"

    override fun run() {
        echo("api.slack.com/apps → Create New App → From a manifest 에 붙여 넣으세요:")
        echo("")
        echo(SLACK_MANIFEST)
        echo("")
        echo("만든 뒤: Install to Workspace → Bot User OAuth Token(xoxb-) 복사,")
        echo("Basic Information → App-Level Tokens 에서 connections:write 토큰(xapp-) 생성 → vesta slack token")
    }

    private companion object {
        val SLACK_MANIFEST = """
            display_information:
              name: Vesta
              description: 집 Mac에서 도는 개인 AI 비서
            features:
              app_home:
                home_tab_enabled: false
                messages_tab_enabled: true
                messages_tab_read_only_enabled: false
              bot_user:
                display_name: Vesta
                always_online: true
            oauth_config:
              scopes:
                bot:
                  - chat:write
                  - im:history
                  - files:read
            settings:
              event_subscriptions:
                bot_events:
                  - message.im
              interactivity:
                is_enabled: true
              org_deploy_enabled: false
              socket_mode_enabled: true
              token_rotation_enabled: false
        """.trimIndent()
    }
}

class PairCommand(
    private val channel: ChannelType,
    private val key: String,
    private val instructions: suspend CliktCommand.(code: String) -> Unit,
) : CliktCommand(name = "pair") {
    override fun help(context: Context) = "내 계정을 연결할 일회용 코드를 만든다 (10분)"

    override fun run() = runBlocking {
        val db = VestaDatabase.open(VestaPaths.default().database).also { it.migrate() }
        val owner = db.ensureOwner(System.getProperty("user.name") ?: "owner")
        val code = PairingCode.generate()
        ExposedChannelBindingStore(db.db).createPairing(owner, channel, PairingCode.hash(code), Duration.ofMinutes(10))
        instructions(code)
        if (BotTokens.read(key) == null) echo("봇 토큰이 없습니다. 먼저 vesta $key token")
        echo("코드는 한 번만 쓸 수 있고, 새로 만들면 이전 코드는 무효입니다.")
    }
}

class ListCommand(private val channel: ChannelType, private val key: String) : CliktCommand(name = "list") {
    override fun help(context: Context) = "연결된 계정"

    override fun run() = runBlocking {
        val db = VestaDatabase.open(VestaPaths.default().database).also { it.migrate() }
        val list = ExposedChannelBindingStore(db.db).list(channel)
        if (list.isEmpty()) echo("연결된 계정이 없습니다. vesta $key pair")
        list.forEach { echo("${it.externalUserId}  사용자 ${it.user.userId.value} (${it.user.role})  세션 ${it.sessionId?.value ?: "-"}") }
    }
}

class UnlinkCommand(private val channel: ChannelType, private val key: String) : CliktCommand(name = "unlink") {
    override fun help(context: Context) = "계정 연결을 끊는다"
    private val id by argument(help = "메신저 사용자 ID (vesta $key list)")

    override fun run() = runBlocking {
        val db = VestaDatabase.open(VestaPaths.default().database).also { it.migrate() }
        echo(if (ExposedChannelBindingStore(db.db).remove(channel, id)) "연결을 끊었습니다: $id" else "없는 계정입니다: $id")
    }
}

class OffCommand(private val key: String, private vararg val accounts: String) : CliktCommand(name = "off") {
    override fun help(context: Context) = "저장한 토큰을 지워 봇을 끈다"

    override fun run() {
        val deleted = accounts.map { BotTokens.delete(key, it) }
        echo(if (deleted.any { it }) "토큰을 지웠습니다." else "저장된 토큰이 없습니다.")
        restartServer()
    }
}

private fun CliktCommand.fail(message: String): Nothing {
    echo(message, err = true)
    throw ProgramResult(1)
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
