package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

private fun source(): Path? = listOfNotNull(System.getenv("VESTA_SRC"), "${System.getProperty("user.home")}/.vesta/src")
    .map { Path.of(it) }.firstOrNull { Files.isRegularFile(it.resolve("install.sh")) }

private const val ONE_LINER = "curl -fsSL https://raw.githubusercontent.com/munbc8308-personal/LocalAI_v2/main/install.sh | sh"

/** 업데이트 (N-06): 받아 둔 저장소로 install.sh를 다시 돌린다 (갱신 → 빌드 → 서비스 다시 시작). 설정·데이터는 그대로 */
class UpdateCommand : CliktCommand(name = "update") {
    override fun help(context: Context) = "새 버전을 받아 다시 설치하고 서버를 다시 시작한다 (설정·데이터는 그대로)"

    override fun run() {
        val src = source() ?: run {
            echo("받아 둔 저장소(~/.vesta/src)가 없습니다. 한 줄 설치로 받으세요:\n  $ONE_LINER", err = true)
            throw ProgramResult(1)
        }
        echo("업데이트: $src (진행 중인 영상 작업은 서버가 다시 켜지며 멈춘다)")
        val process = ProcessBuilder("/bin/sh", src.resolve("install.sh").toString()).inheritIO()
            .apply { environment()["VESTA_SRC"] = src.toString() }.start()
        if (process.waitFor() != 0) throw ProgramResult(1)
    }
}

/**
 * 제거 (N-06): 서비스·프로그램은 지우고, 데이터·키체인·검색 컨테이너·모델은 하나씩 묻는다 (기본은 남김).
 * 되돌릴 수 없으므로 지울 것을 먼저 보여 주고 "삭제"를 입력해야 진행한다. `--dry-run`은 보여 주기만
 */
class UninstallCommand : CliktCommand(name = "uninstall") {
    override fun help(context: Context) = "Vesta를 지운다 (서비스·프로그램). 데이터·토큰·모델은 지울지 따로 묻는다"
    private val dryRun by option("--dry-run", help = "지울 것만 보여 준다").flag()

    private val home: Path = Path.of(System.getProperty("user.home"))
    private val vesta: Path = VestaPaths.default().home
    private val labels = listOf("dev.vesta.server", "dev.vesta.media", "dev.vesta.evolve", "dev.vesta.ollaya", "dev.vesta.reranker")
    private val keychain = listOf("dev.vesta" to "api-token", "dev.vesta.telegram" to "bot-token", "dev.vesta.discord" to "bot-token",
        "dev.vesta.slack" to "bot-token", "dev.vesta.slack" to "app-token")

    override fun run() {
        val agents = home.resolve("Library/LaunchAgents")
        val services = labels.filter { agents.resolve("$it.plist").exists() }
        val programs = listOf(vesta.resolve("app"), vesta.resolve("media-worker"), vesta.resolve("trainer"), vesta.resolve("video/.venv"), vesta.resolve("bin"), home.resolve(".local/bin/vesta"))
            .filter { Files.exists(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        val config = runCatching { ConfigLoader.load(VestaPaths.default().configFile) }.getOrNull()
        val models = listOfNotNull(config?.models?.general?.name, config?.models?.embedding?.name).distinct()
        echo("지울 것 (항상):")
        services.forEach { echo("  서비스 $it") }
        programs.forEach { echo("  프로그램 $it") }
        echo("묻고 지울 것 (기본은 남김):")
        echo("  데이터 $vesta (설정·대화·컬렉션·미디어·학습 기록·영상 모델·받아 둔 저장소)")
        echo("  키체인 dev.vesta.* (API·메신저·A2A 토큰)")
        echo("  SearXNG 컨테이너 (vesta-searxng)")
        echo("  모델 ${models.joinToString()} (Ollama), 결정 모델 (Ollaya)")
        echo("남는 것: Homebrew 패키지(Ollama·ffmpeg·uv·Colima 등). 지우려면 brew uninstall")
        if (dryRun) return
        val console = System.console() ?: run { echo("터미널에서 실행하세요", err = true); throw ProgramResult(1) }
        // 한글 입력이 안 되는 터미널을 위해 delete도 받는다
        if (console.readLine("\n되돌릴 수 없습니다. 진행하려면 '삭제'(또는 delete)를 입력하세요: ")?.trim()?.lowercase() !in setOf("삭제", "delete")) { echo("그만둡니다"); return }
        fun ask(q: String) = console.readLine("$q [y/N] ")?.trim()?.lowercase() in setOf("y", "yes", "ㅛ", "네", "예")
        val removeData = ask("데이터($vesta)도 지울까요? (대화·미디어가 모두 사라집니다. 먼저 vesta export 를 권합니다)")
        val removeKeys = ask("키체인 토큰(dev.vesta.*)도 지울까요?")
        val removeSearch = ask("SearXNG 컨테이너도 지울까요?")
        val removeModels = ask("받은 모델(${models.joinToString()}, 결정 모델)도 지울까요? 다른 프로그램이 쓰지 않을 때만")

        val uid = run("/usr/bin/id", "-u")?.trim()
        services.forEach { label ->
            run("/bin/launchctl", "bootout", "gui/$uid/$label")
            Files.deleteIfExists(agents.resolve("$label.plist"))
            echo("✓ 서비스 $label")
        }
        if (removeSearch) {
            val compose = vesta.resolve("src/infra/searxng/compose.yaml")
            if (compose.exists()) run("/opt/homebrew/bin/docker", "compose", "-f", compose.toString(), "down")
            else run("/opt/homebrew/bin/docker", "rm", "-f", "vesta-searxng")
            echo("✓ SearXNG 컨테이너")
        }
        if (removeModels) {
            models.forEach { run("/opt/homebrew/bin/ollama", "rm", it); echo("✓ 모델 $it") }
            config?.decision?.model?.let { run(ModelsCommand.OLLAYA.toString(), "rm", it); echo("✓ 결정 모델 $it") }
        }
        if (removeKeys) {
            keychain.forEach { (service, account) -> run("/usr/bin/security", "delete-generic-password", "-s", service, "-a", account) }
            // A2A 에이전트 토큰은 계정(에이전트 이름)마다 하나: 더 없을 때까지
            repeat(100) { if (run("/usr/bin/security", "delete-generic-password", "-s", "dev.vesta.a2a") == null) return@repeat }
            echo("✓ 키체인 dev.vesta.*")
        }
        programs.forEach { delete(it); echo("✓ 프로그램 $it") }
        if (removeData) { delete(vesta); echo("✓ 데이터 $vesta") }
        else echo("데이터는 남겼습니다: $vesta (다시 설치하면 그대로 쓴다)")
        echo("끝.")
    }

    private fun delete(path: Path) {
        if (Files.isSymbolicLink(path)) Files.deleteIfExists(path) else path.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
        out
    }.getOrNull()
}
