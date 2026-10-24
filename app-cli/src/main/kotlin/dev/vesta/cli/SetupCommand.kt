package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import dev.vesta.app.BotTokens
import dev.vesta.core.UserRole
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaPaths
import dev.vesta.storage.ExposedUserRepository
import dev.vesta.storage.VestaDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 대화형 첫 설정 (PROJECT.md N-03, M23): 웹 비밀번호 → 메신저(선택) → Tailscale(선택) → Orchard(선택, 유료)를 하나씩 묻는다.
 * 이미 된 것은 건너뛰고, 건너뛴 것은 언제든 다시 `vesta setup`. 비밀번호·토큰은 화면에 보이지 않게 받아 키체인·DB에만 둔다
 */
class SetupCommand : CliktCommand(name = "setup") {
    override fun help(context: Context) = "처음 설정: 웹 비밀번호, 메신저, Tailscale, Orchard를 차례로 묻는다 (이미 된 것은 건너뜀)"

    override fun run() = runBlocking {
        val console = System.console() ?: run {
            echo("터미널에서 실행하세요 (비밀번호·토큰을 화면에 보이지 않게 받는다). 설치 스크립트에서는 vesta setup < /dev/tty", err = true)
            throw ProgramResult(1)
        }
        fun ask(question: String): Boolean = console.readLine("$question [y/N] ")?.trim()?.lowercase() in setOf("y", "yes", "ㅛ", "예", "네")
        fun runCommand(vararg args: String) {
            val command = if (args.first() == "passwd") PasswdCommand() else messengerCommands().first { it.commandName == args.first() }
            try {
                command.parse(args.drop(1))
            } catch (e: ProgramResult) {
                if (e.statusCode != 0) echo("  (건너뜀)")
            } catch (e: CliktError) {
                echo("  ${e.message ?: "실패"} (건너뜀)", err = true)
            }
        }

        val paths = VestaPaths.default()
        val config = ConfigLoader.load(paths.configFile, paths.tunedFile)
        val db = VestaDatabase.open(paths.database).also { it.migrate() }
        val owner = ExposedUserRepository(db.db).list().firstOrNull { it.role == UserRole.OWNER }

        echo("\n1. 웹 UI 비밀번호 (휴대폰·다른 기기에서 로그인)")
        if (owner?.hasPassword == true) echo("  ✓ 이미 있음 (바꾸려면 vesta passwd)")
        else runCommand("passwd")

        echo("\n2. 메신저 (선택): 휴대폰에서 메신저로 Vesta와 대화")
        for ((key, title, ready) in listOf(
            Triple("telegram", "Telegram", BotTokens.read("telegram") != null),
            Triple("discord", "Discord", BotTokens.read("discord") != null),
            Triple("slack", "Slack", BotTokens.read("slack") != null && BotTokens.read("slack", account = BotTokens.APP) != null),
        )) {
            if (ready) { echo("  ✓ $title 토큰 있음 (계정 연결: vesta $key pair)"); continue }
            if (!ask("  $title 봇을 연결할까요?")) continue
            if (key == "slack") echo("  Slack 앱 만들기: vesta slack manifest 의 내용으로 api.slack.com/apps 에서 만든 뒤 토큰을 넣으세요")
            runCommand(key, "token")
            if (BotTokens.read(key) != null && ask("  지금 내 $title 계정을 연결할까요? (일회용 코드)")) runCommand(key, "pair")
        }

        echo("\n3. Tailscale (선택): 집 밖에서 휴대폰으로 웹 UI 접속 (내 tailnet 안에서만, 공유기 포트를 열지 않는다)")
        val tailscale = listOf("/opt/homebrew/bin/tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale").firstOrNull { Files.isExecutable(Path.of(it)) }
        val script = listOfNotNull(System.getenv("VESTA_SRC"), "${System.getProperty("user.home")}/.vesta/src")
            .map { Path.of(it, "scripts", "tailscale-serve.sh") }.firstOrNull { Files.isExecutable(it) }
        when {
            tailscale == null -> echo("  · 없음: Mac App Store 또는 https://tailscale.com/download 에서 설치·로그인한 뒤 다시 vesta setup")
            output(tailscale, "serve", "status")?.contains(config.server.port.toString()) == true -> echo("  ✓ 이미 켜져 있음")
            script == null -> echo("  · 켜기: 저장소의 scripts/tailscale-serve.sh")
            ask("  Tailscale serve로 웹 UI를 내 기기들에 열까요?") -> {
                val process = ProcessBuilder(script.toString()).inheritIO().start()
                echo(if (process.waitFor() == 0) "  ✓ 켰습니다" else "  실패 (Tailscale 로그인 확인)")
            }
        }

        echo("\n4. Orchard (선택, 유료): 캘린더·미리 알림·메일·메모·메시지 등 Apple 앱 연동")
        val orchard = config.mcp.servers["orchard"]
        when {
            orchard == null || !orchard.enabled -> echo("  · 설정에서 꺼져 있음 (mcp.servers.orchard)")
            !Files.isExecutable(Path.of(orchard.command)) -> echo("  · 설치 안 됨: Orchard 앱을 설치·로그인하면 다음 서버 시작부터 자동으로 쓴다 (없어도 나머지는 동작)")
            output(orchard.command, "status") != null -> echo("  ✓ 실행 중")
            else -> echo("  ! 설치됐지만 응답 없음: Orchard 앱을 열고 로그인·권한을 확인하세요")
        }
        echo("\n끝. 점검: vesta doctor · 대화: vesta · 웹: ${config.server.baseUrl}")
    }

    private fun output(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
        text
    }.getOrNull()
}
