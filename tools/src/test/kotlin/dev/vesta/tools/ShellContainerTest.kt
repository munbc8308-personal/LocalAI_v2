package dev.vesta.tools

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.tools.ToolContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path

class ShellContainerTest : FunSpec({
    val ctx = ToolContext(UserContext(UserId(1), UserRole.OWNER))
    fun args(command: String, cwd: String) = buildJsonObject { put("command", JsonPrimitive(command)); put("cwd", JsonPrimitive(cwd)) }

    test("docker run 인자: 네트워크 없음·권한 없음·읽기 전용·내 UID·허용 폴더 하나만·환경 변수는 TZ·HOME만") {
        val c = ShellContainer(docker = "/x/docker", dockerHost = "unix:///s.sock", uid = 501, gid = 20, zone = "Asia/Seoul", probe = { null })
        val run = c.command(Path.of("/Users/me/Documents"), Path.of("/Users/me/Documents/a"), "ls -la")
        run.argv.take(4) shouldBe listOf("/x/docker", "run", "--rm", "--name")
        run.name shouldStartWith ShellContainer.PREFIX
        run.argv.shouldContainInOrder("--network", "none", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "501:20")
        run.argv.shouldContainInOrder("--read-only", "--tmpfs", "/tmp:rw,size=256m", "-e", "TZ=Asia/Seoul", "-e", "HOME=/tmp")
        run.argv.shouldContainInOrder("-v", "/Users/me/Documents:/Users/me/Documents", "-w", "/Users/me/Documents/a", "vesta-shell", "/bin/zsh", "-f", "-c", "ls -la")
        run.argv.count { it == "-v" } shouldBe 1
        run.env shouldBe mapOf("DOCKER_HOST" to "unix:///s.sock")
    }

    test("격리를 쓸 수 없으면 실행하지 않고 고치는 법 (Mac에서 직접으로 바꾸지 않음), 승인 화면에 실행 위치") {
        val home = Files.createTempDirectory("home").toRealPath()
        val marker = home.resolve("ran")
        val shell = ShellTool(PathSandbox(listOf(home.toString()), home), 5, 500, container = ShellContainer(probe = { "격리 실행을 쓸 수 없습니다: Colima가 꺼져 있습니다" }))
        shell.execute(args("touch ran", home.toString()), ctx).let { it.isError shouldBe true; it.output shouldContain "Colima" }
        Files.exists(marker) shouldBe false
        shell.describe(args("ls", home.toString())).let { it.title shouldStartWith "셸 실행 (격리): "; it.body shouldContain "네트워크 없음" }
        ShellTool(PathSandbox(listOf(home.toString()), home), 5, 500).describe(args("ls", home.toString())).title shouldStartWith "셸 실행 (Mac에서 직접): "
        shell.spec.description shouldContain "Linux 컨테이너"
    }

    // 이 Mac에 Colima·vesta-shell이 있을 때만 (없으면 건너뜀). Colima는 홈 아래만 붙일 수 있어 홈 아래 임시 폴더를 쓴다
    val real = ShellContainer()
    test("실제 격리 (Colima 있을 때): 허용 폴더에 쓰기, 다른 홈 폴더·네트워크는 안 됨, 시간 초과면 컨테이너를 끝냄").config(enabled = real.unavailable() == null) {
        val home = Path.of(System.getProperty("user.home"))
        val root = Files.createTempDirectory(home, ".vesta-sbx-test").toRealPath()
        try {
            val shell = ShellTool(PathSandbox(listOf(root.toString()), home), 3, 2_000, container = real)
            shell.execute(args("echo hi > made.txt && cat made.txt && id -u", root.toString()), ctx).let {
                it.isError shouldBe false
                it.output shouldContain "hi"
            }
            Files.readString(root.resolve("made.txt")).trim() shouldBe "hi"
            shell.execute(args("ls ${home}/.vesta 2>&1; ls ${home}/Library 2>&1 | head -1", root.toString()), ctx).output shouldContain "No such file"
            shell.execute(args("python3 -c 'import socket; socket.create_connection((\"1.1.1.1\", 53), 2)'", root.toString()), ctx).let {
                it.isError shouldBe true; it.output shouldContain "unreachable"
            }
            shell.execute(args("touch /etc/x", root.toString()), ctx).output shouldContain "Read-only"
            shell.execute(args("sleep 30", root.toString()), ctx).summary shouldContain "시간 초과"
            Thread.sleep(1_000)
            ShellContainer.exec("/opt/homebrew/bin/docker", "unix://$home/.colima/default/docker.sock", "ps", "-q", "--filter", "name=${ShellContainer.PREFIX}").second.trim() shouldBe ""
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
