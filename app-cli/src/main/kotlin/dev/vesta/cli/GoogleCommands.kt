package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.vesta.app.BotTokens
import dev.vesta.app.GoogleKeychain
import kotlinx.coroutines.runBlocking
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Google 연동 (PROJECT.md 7.34, M40). 주인이 OAuth 클라이언트를 한 번 등록하면, 각 사용자는 웹 설정에서 자기 계정을 연결한다 */
class GoogleCommand : CliktCommand(name = "google") {
    override fun help(context: Context) = "Google 연동 (Gmail·드라이브·시트·문서·캘린더): 앱 등록·상태"
    override fun run() = Unit

    companion object {
        fun create() = GoogleCommand().subcommands(GoogleSetupCommand(), GoogleStatusCommand(), GoogleOffCommand())
    }
}

class GoogleSetupCommand : CliktCommand(name = "setup") {
    override fun help(context: Context) = "Google Cloud에서 만든 OAuth 클라이언트(웹 애플리케이션)를 등록한다: ID는 설정, 비밀은 키체인 (서버 다시 시작)"
    private val clientId by option("--client-id", help = "OAuth 클라이언트 ID (….apps.googleusercontent.com)")

    override fun run() = runBlocking {
        val store = UserStore()
        echo("1. console.cloud.google.com 에서 프로젝트를 만들고, API 라이브러리에서 Gmail·Drive·Sheets·Docs·Calendar API를 켭니다")
        echo("2. Google 인증 플랫폼 > 대상: 외부, 게시 상태를 '프로덕션'으로 (테스트면 7일마다 연결이 끊깁니다. 검수 없이 경고 화면·100명까지)")
        echo("3. 클라이언트 > 클라이언트 만들기 > 웹 애플리케이션, 승인된 리디렉션 URI (모두 추가, 연결은 이 주소로 연 화면에서):")
        dev.vesta.app.googleRedirectUris(store.config).forEach { echo("     $it") }
        val id = (clientId ?: run { echo("클라이언트 ID: ", trailingNewline = false); readlnOrNull() })?.trim().orEmpty()
        if (!ID.matches(id)) { echo("클라이언트 ID 형식이 아닙니다 (….apps.googleusercontent.com)", err = true); throw ProgramResult(1) }
        val console = System.console() ?: run { echo("터미널에서 실행하세요 (비밀 입력을 숨기려고 콘솔이 필요합니다)", err = true); throw ProgramResult(1) }
        val secret = console.readPassword("클라이언트 보안 비밀번호 (입력이 보이지 않습니다): ")?.let(::String)?.trim().orEmpty()
        if (secret.isEmpty() || secret.any { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }) { echo("취소했습니다.", err = true); throw ProgramResult(1) }
        if (!BotTokens.writeKeychain(GoogleKeychain.SERVICE, GoogleKeychain.CLIENT_SECRET, secret)) { echo("키체인에 저장하지 못했습니다", err = true); throw ProgramResult(1) }
        writeGoogleConfig(store, enabled = true, id)
        echo("저장했습니다: ${store.paths.configFile} (google), 비밀은 키체인 ${GoogleKeychain.SERVICE}")
        restartServer()
        echo("이제 각 사용자가 웹 설정 > Google 에서 자기 계정을 연결합니다.")
    }
}

class GoogleStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "Google 연동 설정과 사용자별 연결 상태"
    override fun run() = runBlocking {
        val store = UserStore()
        val config = store.config.google
        echo("설정: " + if (config.enabled && config.clientId.isNotBlank()) "켜짐 (${config.clientId.take(12)}…)" else "꺼짐 (켜기: vesta google setup)")
        echo("클라이언트 비밀: " + if (GoogleKeychain.clientSecret() != null) "키체인에 있음" else "없음")
        echo("돌아오는 주소: ${dev.vesta.app.googleRedirectUris(store.config).joinToString()}")
        store.service.list().filter { it.role != dev.vesta.core.UserRole.GUEST }.forEach { user ->
            echo("  ${user.name}: " + (GoogleKeychain.read(user.id)?.email?.let { "연결됨 · $it" } ?: "연결 안 함"))
        }
    }
}

class GoogleOffCommand : CliktCommand(name = "off") {
    override fun help(context: Context) = "Google 연동을 끈다 (--forget: 클라이언트 비밀과 사용자 연결도 키체인에서 지운다)"
    private val forget by option("--forget", help = "키체인 비밀·연결도 지운다").flag()
    override fun run() = runBlocking {
        val store = UserStore()
        writeGoogleConfig(store, enabled = false, store.config.google.clientId)
        if (forget) {
            BotTokens.deleteKeychain(GoogleKeychain.SERVICE, GoogleKeychain.CLIENT_SECRET)
            store.service.list().forEach { GoogleKeychain.delete(it.id) }
            echo("키체인의 Google 비밀·연결을 지웠습니다 (Google 쪽 권한은 myaccount.google.com/permissions 에서 지우세요)")
        }
        echo("껐습니다.")
        restartServer()
    }
}

private val ID = Regex("^[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$")

private fun writeGoogleConfig(store: UserStore, enabled: Boolean, clientId: String) {
    val file = store.paths.configFile
    val block = buildString {
        appendLine("google:")
        appendLine("  enabled: $enabled")
        appendLine("  clientId: \"$clientId\"")
        store.config.google.redirectUri.takeIf { it.isNotBlank() }?.let { appendLine("  redirectUri: \"$it\"") }
    }
    val text = if (file.exists()) file.readText() else ""
    file.writeText(replaceTopLevelBlock(text, "google", block))
}
