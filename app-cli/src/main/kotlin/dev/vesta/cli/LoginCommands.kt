package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking

/** 다른 PC에서 Vesta 서버에 로그인 (RC-01). 이후 `vesta`·`vesta -p`가 그 서버에 붙는다 */
class LoginCommand : CliktCommand(name = "login") {
    override fun help(context: Context) =
        "다른 PC에서 Vesta 서버에 로그인한다 (웹과 같은 이름·비밀번호). 이후 vesta 대화가 그 서버로 간다"
    private val url by argument("주소", help = "서버 주소. 예: https://vesta.example.com")
    private val name by option("--name", "-n", help = "로그인 이름 (웹 로그인 이름, Mac 주인은 비워도 됨)")

    override fun run() = runBlocking {
        val server = RemoteLogin.normalize(url).getOrElse { echo(it.message, err = true); throw ProgramResult(1) }
        val console = System.console()
        val login = name ?: run {
            if (console == null) null else console.readLine("이름 (Mac 주인은 Enter): ")?.trim()?.ifEmpty { null }
        }
        // 비밀번호는 명령줄 인자로 받지 않는다 (프로세스 목록·셸 기록에 남는다)
        val secret = console?.readPassword("비밀번호: ")?.let(::String)
            ?: run { echo("비밀번호를 입력받을 터미널이 없습니다", err = true); throw ProgramResult(1) }
        val result = RemoteLogin.login(server, login, secret).getOrElse { echo(it.message, err = true); throw ProgramResult(1) }
        RemoteLogin.load()?.takeIf { it.url != server || it.session != result.session }?.let { RemoteLogin.logout(it) }
        RemoteLogin.save(result)
        echo("${server}에 ${result.name}(으)로 로그인했습니다. (로그인 정보: ${RemoteLogin.file()})")
        // 다시 login을 치는 실수가 있었다 (Windows 시험): 다음에 칠 명령을 그대로 보여 준다
        val launcher = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "vesta.bat" else "vesta"
        echo("다음: 프로젝트 폴더로 가서 login 없이  $launcher  만 실행하면 대화가 열립니다.")
        echo("프로젝트 폴더에서 vesta를 실행하면 파일·셸 도구가 이 PC의 그 폴더에서 돕니다 (쓰기·셸은 이 터미널에서 승인). 생각(모델)은 서버가 합니다.")
    }
}

/** 로그아웃 (RC-02): 서버의 세션과 로컬 로그인 파일을 지운다 */
class LogoutCommand : CliktCommand(name = "logout") {
    override fun help(context: Context) = "vesta login 으로 붙은 서버에서 로그아웃한다"

    override fun run() = runBlocking {
        val login = RemoteLogin.load() ?: run { echo("로그인돼 있지 않습니다."); return@runBlocking }
        val remote = RemoteLogin.logout(login)
        RemoteLogin.delete()
        echo(if (remote) "${login.url}에서 로그아웃했습니다." else "로그인 정보를 지웠습니다 (서버에 닿지 않아 서버 쪽 세션은 30일 뒤 끝납니다).")
    }
}
