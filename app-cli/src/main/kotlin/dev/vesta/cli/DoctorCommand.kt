package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import dev.vesta.app.ApiToken
import dev.vesta.app.BotTokens
import dev.vesta.app.HardwareProfile
import dev.vesta.app.MacSystem
import dev.vesta.core.UserRole
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaConfig
import dev.vesta.core.config.VestaPaths
import dev.vesta.inference.HttpClients
import dev.vesta.inference.OllamaAdmin
import dev.vesta.storage.ExposedUserRepository
import dev.vesta.storage.VestaDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 설치 점검 (PROJECT.md N-04, M23): 구성 요소마다 상태와 고치는 명령. `vesta status`(실행 상태)를 넓혀 설치가 덜 된 곳까지 본다.
 * 꼭 필요한 것이 빠지면 ✗(끝 코드 1), 선택 기능이 꺼져 있으면 ·, 동작은 하지만 손볼 것은 !
 */
class DoctorCommand : CliktCommand(name = "doctor") {
    override fun help(context: Context) = "설치 상태를 점검하고 고치는 명령을 알려 준다"

    internal enum class Level(val mark: String) { OK("✓"), WARN("!"), OFF("·"), FAIL("✗") }
    internal data class Finding(val section: String, val name: String, val level: Level, val detail: String, val fix: String? = null)

    override fun run() = runBlocking {
        val findings = Doctor().run()
        var section = ""
        findings.forEach { f ->
            if (f.section != section) { section = f.section; echo("\n[$section]") }
            echo("${f.level.mark} ${pad(f.name, 16)} ${f.detail}")
            f.fix?.let { echo("    → $it") }
        }
        val failed = findings.count { it.level == Level.FAIL }
        val warned = findings.count { it.level == Level.WARN }
        echo("\n" + if (failed == 0) "문제 없음${if (warned > 0) " (손볼 것 ${warned}개)" else ""}" else "고칠 것 ${failed}개${if (warned > 0) ", 손볼 것 ${warned}개" else ""}")
        if (failed > 0) throw ProgramResult(1)
    }
}

/** 터미널 폭 기준으로 채운다 (한글·한자는 두 칸) */
internal fun pad(text: String, width: Int): String {
    val shown = text.sumOf { c -> if (c.code in 0x1100..0x115F || c.code in 0x2E80..0xA4CF || c.code in 0xAC00..0xD7A3 || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFF60) 2L else 1L }.toInt()
    return text + " ".repeat((width - shown).coerceAtLeast(1))
}

internal class Doctor(private val home: Path = Path.of(System.getProperty("user.home"))) {
    private val out = mutableListOf<DoctorCommand.Finding>()
    private var section = ""
    private fun add(name: String, level: DoctorCommand.Level, detail: String, fix: String? = null) { out += DoctorCommand.Finding(section, name, level, detail, fix) }
    private fun ok(name: String, detail: String) = add(name, DoctorCommand.Level.OK, detail)
    private fun warn(name: String, detail: String, fix: String?) = add(name, DoctorCommand.Level.WARN, detail, fix)
    private fun off(name: String, detail: String, fix: String?) = add(name, DoctorCommand.Level.OFF, detail, fix)
    private fun fail(name: String, detail: String, fix: String?) = add(name, DoctorCommand.Level.FAIL, detail, fix)

    suspend fun run(): List<DoctorCommand.Finding> {
        val paths = VestaPaths.default()
        section = "시스템"; system()
        section = "도구"; tools()
        section = "설정"
        val config = runCatching { ConfigLoader.load(paths.configFile, paths.tunedFile) }.onSuccess {
            if (Files.exists(paths.configFile)) ok("설정 파일", paths.configFile.toString())
            else warn("설정 파일", "없음 (기본값으로 동작)", "vesta config init")
        }.onFailure { fail("설정 파일", "읽지 못함: ${it.message?.take(200)}", "vesta config show 로 오류 위치 확인") }.getOrNull() ?: return out
        plan(config)
        val db = runCatching { VestaDatabase.open(paths.database).also { it.migrate() } }
            .onSuccess { ok("DB", "${paths.database} (schema v${it.schemaVersion()})") }
            .onFailure { fail("DB", "열지 못함: ${it.message}", "~/.vesta 권한 확인") }.getOrNull()
        val http = HttpClients.local(requestTimeoutSeconds = 5)
        try {
            section = "서비스"; services()
            section = "엔진·모델"; engines(config, http)
            section = "Vesta"; vesta(config, http, db)
            section = "메신저 (선택)"; messengers(config)
            section = "셸"; shell(config)
            section = "선택 기능"; optional(config)
        } finally {
            http.close()
        }
        return out
    }

    private fun system() {
        val arch = System.getProperty("os.arch")
        if (arch == "aarch64") ok("Apple Silicon", "arm64") else fail("Apple Silicon", "$arch — Vesta는 Apple Silicon Mac만 지원한다", null)
        ok("macOS", command("/usr/bin/sw_vers", "-productVersion") ?: "?")
        val memory = MacSystem.totalMemoryBytes()
        val plan = HardwareProfile.recommend(memory)
        if (memory < 15_000_000_000L) warn("메모리", "${gb(memory)} — 16GB 미만은 권하지 않는다", null) else ok("메모리", "${gb(memory)} (${plan.tier})")
        val free = ModelsCommand.freeBytes()
        when {
            free < 20_000_000_000L -> fail("디스크", "남은 공간 ${gb(free)}", "모델·영상을 정리 (ollama rm, ~/.vesta/media)")
            free < plan.downloadGigabytes * 1_000_000_000L + 20_000_000_000L -> warn("디스크", "남은 공간 ${gb(free)} — 모델(약 ${plan.downloadGigabytes}GB)을 새로 받기엔 빠듯함", null)
            else -> ok("디스크", "남은 공간 ${gb(free)}")
        }
    }

    private fun tools() {
        fun binary(name: String, path: String, fix: String, required: Boolean = true) {
            if (Files.isExecutable(Path.of(path))) ok(name, path) else if (required) fail(name, "없음 ($path)", fix) else off(name, "없음", fix)
        }
        binary("Homebrew", "/opt/homebrew/bin/brew", "/bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"")
        val java = command("/usr/libexec/java_home", "-v", "21")
        if (java != null) ok("JDK 21+", java) else fail("JDK 21+", "없음", "brew install openjdk@21 && sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk")
        val clt = command("/usr/bin/xcode-select", "-p")
        if (clt != null) ok("Xcode 도구", clt) else warn("Xcode 도구", "없음 (이어 말하기 도우미를 만들 수 없음)", "xcode-select --install")
        binary("Ollama", "/opt/homebrew/bin/ollama", "brew install ollama && brew services start ollama")
        binary("Ollaya", ModelsCommand.OLLAYA.toString(), "scripts/setup-macos.sh (Ollaya 설치)")
        binary("ffmpeg", "/opt/homebrew/bin/ffmpeg", "brew install ffmpeg")
        binary("uv", "/opt/homebrew/bin/uv", "brew install uv")
        binary("Colima", "/opt/homebrew/bin/colima", "brew install colima docker docker-compose")
    }

    private fun plan(config: VestaConfig) {
        val plan = HardwareProfile.recommend(MacSystem.totalMemoryBytes())
        val general = config.models.general?.name
        when {
            general.isNullOrBlank() -> fail("메인 모델", "설정에 없음", "vesta config init")
            general == plan.general -> ok("메인 모델", "$general (이 Mac에 맞음)")
            else -> ok("메인 모델", "$general (이 Mac 권장: ${plan.general})")
        }
    }

    private fun services() {
        val uid = command("/usr/bin/id", "-u") ?: return
        for ((label, required) in listOf("dev.vesta.server" to true, "dev.vesta.ollaya" to true, "dev.vesta.media" to false, "dev.vesta.evolve" to false)) {
            val printed = command("/bin/launchctl", "print", "gui/$uid/$label")
            when {
                printed == null && required -> fail(label, "등록 안 됨", "scripts/install-vesta.sh")
                printed == null -> off(label, "등록 안 됨", "scripts/install-vesta.sh")
                label == "dev.vesta.evolve" -> ok(label, "등록됨 (매주 일요일 03:00)")
                Regex("state = running").containsMatchIn(printed) -> ok(label, "실행 중")
                else -> if (required) fail(label, "등록됐지만 실행 중이 아님", "launchctl kickstart -k gui/$uid/$label (로그: ~/.vesta/logs)")
                    else warn(label, "등록됐지만 실행 중이 아님", "launchctl kickstart -k gui/$uid/$label")
            }
        }
    }

    private suspend fun engines(config: VestaConfig, http: HttpClient) {
        val admin = OllamaAdmin(config.inference.ollamaUrl, http)
        val local = runCatching { admin.localModels().map { it.name }.toSet() }
        local.onSuccess { ok("Ollama", "${config.inference.ollamaUrl} (v${runCatching { admin.version() }.getOrDefault("?")})") }
            .onFailure { fail("Ollama", "연결 안 됨 (${config.inference.ollamaUrl})", "brew services start ollama") }
        local.getOrNull()?.let { have ->
            val missing = ModelsCommand.ollamaModels(config).filter { it !in have && "$it:latest" !in have }
            if (missing.isEmpty()) ok("모델", ModelsCommand.ollamaModels(config).joinToString())
            else fail("모델", "받지 않음: ${missing.joinToString()}", "vesta models pull")
        }
        val ollaya = runCatching {
            val response = http.get("${config.decision.baseUrl}/api/version")
            require(response.status.isSuccess())
        }
        if (!config.decision.enabled) off("결정 모델", "꺼짐 (decision.enabled)", null)
        else if (ollaya.isFailure) fail("Ollaya", "연결 안 됨 (${config.decision.baseUrl})", "scripts/install-launchd.sh (dev.vesta.ollaya)")
        else {
            val decided = ModelsCommand.ollayaModels(ModelsCommand.OLLAYA)
            val model = config.decision.model
            when {
                decided == null -> warn("결정 모델", "$model (Ollaya 모델 목록을 읽지 못함)", null)
                model in decided || "$model:latest" in decided -> ok("결정 모델", "$model (Ollaya)")
                else -> fail("결정 모델", "받지 않음: $model", "vesta models pull")
            }
        }
        if (config.web.mode == dev.vesta.core.config.WebMode.OFF) off("SearXNG", "웹 검색 꺼짐", null)
        else runCatching {
            val response = http.get("${config.web.searxngUrl}/search?q=vesta&format=json")
            require(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        }.onSuccess { ok("SearXNG", config.web.searxngUrl) }
            .onFailure { fail("SearXNG", "응답 없음 (${it.message})", "colima start && scripts/setup-macos.sh (SearXNG 컨테이너)") }
    }

    private suspend fun vesta(config: VestaConfig, http: HttpClient, db: VestaDatabase?) {
        val server = runCatching { http.get("${config.server.baseUrl}/api/health").bodyAsText() }
        server.onSuccess { ok("서버", config.server.baseUrl) }.onFailure { fail("서버", "응답 없음 (${config.server.baseUrl})", "scripts/install-vesta.sh (로그: ~/.vesta/logs/server.err.log)") }
        if (ApiToken.read() != null) ok("API 토큰", "키체인 dev.vesta / api-token") else warn("API 토큰", "없음 (서버가 처음 켜질 때 만든다)", "서버를 한 번 켠다")
        val owner = db?.let { runCatching { ExposedUserRepository(it.db).list().firstOrNull { u -> u.role == UserRole.OWNER } }.getOrNull() }
        when {
            owner == null -> warn("웹 비밀번호", "확인 불가", "vesta passwd")
            owner.hasPassword -> ok("웹 비밀번호", "설정됨 (${owner.name})")
            else -> warn("웹 비밀번호", "없음 — 웹 UI·휴대폰에서 로그인할 수 없다", "vesta passwd")
        }
        fun env(name: String, dir: Path, fix: String, required: Boolean = true) {
            if (Files.isExecutable(dir.resolve(".venv/bin/python"))) ok(name, dir.toString())
            else if (required) warn(name, "환경 없음 ($dir)", fix) else off(name, "환경 없음", fix)
        }
        env("미디어 워커", home.resolve(".vesta/media-worker"), "scripts/install-vesta.sh (음성 인식·그림)")
        env("학습기", home.resolve(".vesta/trainer"), "scripts/install-vesta.sh", required = false)
        val speak = Path.of(config.media.speakBinary.replaceFirst(Regex("^~"), home.toString()))
        if (Files.isExecutable(speak)) ok("이어 말하기", speak.toString()) else off("이어 말하기", "도우미 없음 (문장마다 say로 대신)", "xcode-select --install 후 scripts/install-vesta.sh")
        val clips = config.media.clips
        if (clips == null) {
            val plan = HardwareProfile.recommend(MacSystem.totalMemoryBytes())
            off("영상 클립", "꺼짐" + if (plan.clips) " (이 Mac은 돌릴 수 있음)" else "", if (plan.clips) "video/README.md (Wan2.2 받기·변환 후 media.clips 설정)" else null)
        } else {
            val model = Path.of(clips.modelDir.replaceFirst(Regex("^~"), home.toString()))
            if (Files.exists(model.resolve("model.safetensors"))) ok("영상 클립", "${clips.model} ($model)")
            else fail("영상 클립", "설정은 켜졌지만 모델이 없음 ($model)", "video/README.md 대로 받기·변환, 또는 config에서 media.clips 지우기")
        }
    }

    private fun messengers(config: VestaConfig) {
        fun channel(name: String, enabled: Boolean, ready: Boolean, fix: String) {
            when {
                !enabled -> off(name, "꺼짐 (설정)", null)
                ready -> ok(name, "토큰 있음")
                else -> off(name, "토큰 없음", fix)
            }
        }
        channel("Telegram", config.channels.telegram.enabled, BotTokens.read("telegram") != null, "vesta telegram token")
        channel("Discord", config.channels.discord.enabled, BotTokens.read("discord") != null, "vesta discord token")
        channel("Slack", config.channels.slack.enabled, BotTokens.read("slack") != null && BotTokens.read("slack", account = BotTokens.APP) != null, "vesta slack token")
    }

    /** 셸 격리 실행 (M31 X-06) */
    private fun shell(config: VestaConfig) {
        val docker = "/opt/homebrew/bin/docker"
        val host = "unix://$home/.colima/default/docker.sock"
        val problem = dev.vesta.tools.ShellContainer.defaultProbe(docker, host, config.tools.shellImage)
        when {
            !config.tools.shellEnabled -> off("셸", "꺼짐 (tools.shellEnabled)", null)
            config.tools.shellMode == "host" -> warn("셸", "Mac에서 직접 실행 (격리 없음)", "tools.shellMode: sandbox (MEMBER 셸은 그래도 격리: " + (if (problem == null) "준비됨" else "준비 안 됨") + ")")
            problem == null -> ok("셸", "격리 실행 (${config.tools.shellImage}, 네트워크 없음·허용 폴더 하나만)")
            else -> fail("셸", problem.removePrefix("격리 실행을 쓸 수 없습니다: "), if ("이미지" in problem) "scripts/shell-sandbox.sh" else "colima start")
        }
    }

    private fun optional(config: VestaConfig) {
        val orchard = config.mcp.servers["orchard"]
        when {
            orchard == null || !orchard.enabled -> off("Orchard", "꺼짐 (Apple 앱 연동, 유료)", "vesta setup")
            !Files.isExecutable(Path.of(orchard.command)) -> off("Orchard", "설치 안 됨 (캘린더·메일·메모 등 Apple 앱 연동, 유료·선택)", "vesta setup")
            command(orchard.command, "status") != null -> ok("Orchard", "실행 중")
            else -> warn("Orchard", "설정은 켜졌지만 응답 없음 (${orchard.command})", "Orchard 앱 실행·로그인")
        }
        val tailscale = listOf("/opt/homebrew/bin/tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale").firstOrNull { Files.isExecutable(Path.of(it)) }
        when {
            tailscale == null -> off("Tailscale", "없음 (휴대폰에서 집 밖 접속)", "brew install --cask tailscale 후 scripts/tailscale-serve.sh")
            command(tailscale, "serve", "status")?.contains("8787") == true -> ok("Tailscale", "serve로 8787 공개 중 (tailnet 안에서만)")
            else -> off("Tailscale", "설치됨, serve 안 함", "scripts/tailscale-serve.sh")
        }
        // 지인 공개 (M26): 켜져 있으면 터널·관문이 막고 있는지까지 본다
        publicChecks(config.publicAccess, config.server.port).forEach { (mark, detail) ->
            when (mark) {
                "✓" -> ok("지인 공개", detail)
                "!" -> warn("지인 공개", detail, "vesta public status")
                "✗" -> fail("지인 공개", detail, "vesta public status")
                else -> off("지인 공개", "꺼짐 (초대한 지인에게 주소 하나로 공개)", "vesta public setup")
            }
        }
    }

    /** 명령 출력 첫 줄들 (실패하면 null) */
    private fun command(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
        text
    }.getOrNull()
}
