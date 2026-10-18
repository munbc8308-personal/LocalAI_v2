package dev.vesta.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.tools.ApprovalDetails
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 셸 실행 (PROJECT.md T-07, 셸 실행 정책).
 * - 위험 등급 EXEC: 실행할 때마다 승인. "항상 허용" 없음. 승인 화면에 명령 전체·작업 폴더·이유.
 * - 차단 목록: 승인해도 실행하지 않는 명령 (sudo, rm -rf /, 디스크 포맷, 원격 스크립트 실행 …)
 * - 작업 폴더는 허용 폴더 안. 타임아웃(기본 60초) 후 프로세스 트리째 종료. 출력 길이 제한. 백그라운드 실행 금지.
 * - 환경 변수는 최소한만 넘긴다 (API 키·토큰 등 제외).
 */
class ShellTool(
    private val sandbox: PathSandbox,
    private val timeoutSeconds: Long,
    private val maxOutputChars: Int,
) : Tool {
    override val spec = ToolSpec(
        "run_shell",
        "셸 명령을 실행한다 (/bin/zsh). 매번 사용자 승인이 필요하다. 파일 정리·빌드·검색처럼 다른 도구로 안 되는 일에만 쓴다. " +
            "백그라운드 실행(&)·sudo는 쓸 수 없다. 타임아웃 ${timeoutSeconds}초.",
        schema(
            "command" to prop("string", "실행할 명령"),
            "cwd" to prop("string", "작업 폴더 (허용 폴더 안)"),
            "reason" to prop("string", "이 명령이 필요한 이유 (승인 화면에 표시)"),
            required = listOf("command", "cwd"),
        ),
    )
    override val risk = RiskLevel.EXEC

    override fun describe(arguments: JsonObject): ApprovalDetails {
        val command = arguments.string("command").orEmpty()
        return ApprovalDetails(
            title = "셸 실행: $command",
            body = "명령: $command\n작업 폴더: ${arguments.string("cwd")}\n이유: ${arguments.string("reason") ?: "(없음)"}",
            warning = blocked(command)?.let { "차단 목록에 걸림 — 승인해도 실행하지 않음: $it" }
                ?: if (DANGEROUS.containsMatchIn(command)) "파일 삭제·덮어쓰기가 포함된 명령입니다" else null,
        )
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val command = arguments.string("command") ?: return fail("command가 필요함")
        blocked(command)?.let { return fail("차단된 명령 ($it)") }
        val cwd = when (val c = sandbox.check(arguments.string("cwd") ?: return fail("cwd가 필요함"))) {
            is PathSandbox.Check.Denied -> return fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        if (!cwd.toFile().isDirectory) return fail("작업 폴더가 없음: ${sandbox.display(cwd)}")

        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder("/bin/zsh", "-f", "-c", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .also { builder ->
                    val env = builder.environment()
                    val keep = env.filterKeys { it in SAFE_ENV }
                    env.clear()
                    env.putAll(keep)
                    env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                    env["TERM"] = "dumb"
                }
                .start()
            coroutineScope {
                val output = async { process.inputStream.bufferedReader().use { reader -> readLimited(reader, maxOutputChars) } }
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
                val (text, truncated) = output.await()
                val code = if (finished) process.exitValue() else -1
                val status = when {
                    !finished -> "시간 초과 (${timeoutSeconds}초) — 종료함"
                    code == 0 -> "종료 코드 0"
                    else -> "종료 코드 $code"
                }
                ToolResult(
                    output = "$ $command\n$text" + (if (truncated) "\n… (출력 ${maxOutputChars}자에서 잘림)" else "") + "\n[$status]",
                    isError = !finished || code != 0,
                    summary = "$status · 출력 ${text.lines().size}줄",
                )
            }
        }
    }

    private fun readLimited(reader: java.io.BufferedReader, limit: Int): Pair<String, Boolean> {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        var truncated = false
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            if (out.length < limit) out.appendRange(buffer, 0, minOf(n, limit - out.length)) else truncated = true
            if (out.length >= limit && n > 0) truncated = true
        }
        return out.toString() to truncated
    }

    companion object {
        private val SAFE_ENV = setOf("HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "SHELL")

        /** 승인과 관계없이 실행하지 않는 패턴 (PROJECT.md 셸 실행 정책) */
        private val BLOCKLIST = listOf(
            "sudo/su" to Regex("(^|[;&|`(]\\s*)(sudo|su|doas)\\b"),
            "루트·홈 통째로 삭제" to Regex("\\brm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+(/|~|\\\$HOME|/\\*|~/\\*|\\*)(\\s|$)"),
            "디스크 포맷·삭제" to Regex("\\b(mkfs\\S*|diskutil\\s+(erase\\w*|partition\\w*|zero\\w*|secure\\w*|apfs\\s+delete\\w*|reformat)|newfs_\\w+|fdisk|gpt\\s+destroy)\\b"),
            "디스크 직접 쓰기" to Regex("\\bdd\\s+.*\\bof=/dev/|>\\s*/dev/(r?disk|sd)"),
            "원격 스크립트 실행" to Regex("\\b(curl|wget|fetch)\\b[^|]*\\|\\s*(sudo\\s+)?(sh|bash|zsh|python3?|perl|ruby|node)\\b"),
            "시스템 설정 변경" to Regex("\\b(defaults\\s+write|csrutil|spctl|systemsetup|scutil|nvram|pmset|launchctl|kextload|tccutil)\\b"),
            "전원" to Regex("\\b(shutdown|reboot|halt)\\b"),
            "권한 대량 변경" to Regex("\\bchmod\\s+(-R\\s+)?[0-7]*777\\s+(/|~)"),
            "포크 폭탄" to Regex(":\\(\\)\\s*\\{"),
            "백그라운드 실행" to Regex("(^|[^&])&\\s*($|[;)])|\\b(nohup|disown|setsid|screen|tmux)\\b"),
            "키체인 접근" to Regex("\\bsecurity\\s+(find|dump|export|delete)"),
            "앱 자동화(osascript)" to Regex("\\bosascript\\b"),
        )

        private val DANGEROUS = Regex("\\b(rm|mv|cp\\s+-f|truncate|shred)\\b|>\\s*[^&]")

        fun blocked(command: String): String? = BLOCKLIST.firstOrNull { (_, regex) -> regex.containsMatchIn(command) }?.first
    }
}
