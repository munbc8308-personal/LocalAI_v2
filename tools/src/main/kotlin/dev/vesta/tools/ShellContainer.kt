package dev.vesta.tools

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 셸 격리 실행 (PROJECT.md 7.25 X-01·X-05, M31). Colima(Docker)의 `vesta-shell` 이미지에서 `docker run --rm`.
 * - 네트워크 없음, 모든 권한 버림·no-new-privileges, 내 UID·GID, 메모리·CPU·프로세스 수 제한
 * - 루트 파일 시스템 읽기 전용, `/tmp`만 쓰기 (HOME도 /tmp)
 * - [root](작업 폴더가 든 허용 폴더) 하나만 같은 경로로 붙인다. 환경 변수는 TZ만
 */
class ShellContainer(
    private val docker: String = "/opt/homebrew/bin/docker",
    private val dockerHost: String = "unix://" + System.getProperty("user.home") + "/.colima/default/docker.sock",
    private val image: String = "vesta-shell",
    private val memory: String = "1g",
    private val cpus: String = "2",
    private val pids: Int = 256,
    private val uid: Long = unixId("u"),
    private val gid: Long = unixId("g"),
    private val zone: String = java.time.ZoneId.systemDefault().id,
    /** 컨테이너를 쓸 수 있는지 (없으면 null, 있으면 고치는 법). 30초 동안 결과를 기억한다 */
    private val probe: () -> String? = { defaultProbe(docker, dockerHost, image) },
) {
    data class Run(val argv: List<String>, val env: Map<String, String>, val name: String)

    @Volatile private var checked: Pair<Long, String?>? = null

    fun unavailable(): String? {
        val now = System.currentTimeMillis()
        checked?.takeIf { now - it.first < 30_000 }?.let { return it.second }
        return probe().also { checked = now to it }
    }

    fun command(root: Path, cwd: Path, command: String): Run {
        val name = "$PREFIX${UUID.randomUUID().toString().take(12)}"
        val argv = listOf(
            docker, "run", "--rm", "--name", name,
            "--network", "none", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--user", "$uid:$gid", "--memory", memory, "--memory-swap", memory, "--cpus", cpus, "--pids-limit", pids.toString(),
            "--read-only", "--tmpfs", "/tmp:rw,size=256m", "-e", "TZ=$zone", "-e", "HOME=/tmp",
            "-v", "$root:$root", "-w", cwd.toString(),
            image, "/bin/zsh", "-f", "-c", command,
        )
        return Run(argv, mapOf("DOCKER_HOST" to dockerHost), name)
    }

    fun kill(name: String) {
        runCatching {
            ProcessBuilder(docker, "kill", name).also { it.environment()["DOCKER_HOST"] = dockerHost }
                .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    /** 서버가 꺼지며 남은 컨테이너 지우기 (X-05) */
    fun cleanup(): Int = runCatching {
        val list = exec(docker, dockerHost, "ps", "-aq", "--filter", "name=$PREFIX").second.lines().filter { it.isNotBlank() }
        if (list.isNotEmpty()) exec(docker, dockerHost, "rm", "-f", *list.toTypedArray())
        list.size
    }.getOrDefault(0)

    companion object {
        const val PREFIX = "vesta-shell-"

        private fun unixId(kind: String): Long = runCatching {
            val p = ProcessBuilder("/usr/bin/id", "-$kind").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().toLong().also { p.waitFor(5, TimeUnit.SECONDS) }
        }.getOrDefault(if (kind == "u") 501L else 20L)

        internal fun exec(docker: String, host: String, vararg args: String): Pair<Int, String> {
            val p = ProcessBuilder(listOf(docker) + args).also { it.environment()["DOCKER_HOST"] = host }.redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to out }
            return p.exitValue() to out
        }

        fun defaultProbe(docker: String, host: String, image: String): String? {
            if (!java.io.File(docker).canExecute()) return "격리 실행을 쓸 수 없습니다: docker가 없습니다 ($docker). scripts/setup-macos.sh로 Colima를 설치하세요"
            val (code, _) = runCatching { exec(docker, host, "image", "inspect", image) }.getOrDefault(-1 to "")
            return when (code) {
                0 -> null
                -1 -> "격리 실행을 쓸 수 없습니다: Docker가 응답하지 않습니다. 'colima start'로 켜 주세요"
                else -> if (runCatching { exec(docker, host, "info").first }.getOrDefault(-1) != 0) "격리 실행을 쓸 수 없습니다: Colima가 꺼져 있습니다. 'colima start'로 켜 주세요"
                    else "격리 실행을 쓸 수 없습니다: $image 이미지가 없습니다. scripts/shell-sandbox.sh로 만드세요"
            }
        }
    }
}
