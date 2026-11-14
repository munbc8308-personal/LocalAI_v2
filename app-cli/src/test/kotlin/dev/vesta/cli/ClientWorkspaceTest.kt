package dev.vesta.cli

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files

class ClientWorkspaceTest : FunSpec({
    fun approval(id: String, tool: String, risk: RiskLevel, arguments: String) =
        AgentEvent.ApprovalRequested(id, tool, risk, ApprovalDetails("t", ""), null, arguments = arguments)

    test("다른 PC 도구 실행 (RC-07): 읽기는 바로, 쓰기·셸은 이 터미널에서 같은 인자로 승인한 것만 한 번") {
        val root = Files.createTempDirectory("vesta-client").toRealPath()
        Files.writeString(root.resolve("a.txt"), "hello")
        val client = ClientWorkspace(root.toString())
        client.specs().map { it.name }.toSet() shouldBe setOf("list_files", "read_file", "search_files", "write_file", "make_directory", "move_file", "apply_patch", "run_shell")

        client.run("read_file", """{"path":"a.txt"}""").output shouldContain "hello"
        // 작업 폴더 밖·비밀 파일은 읽지 않는다
        client.run("read_file", """{"path":"${root.parent.toString().replace("\\", "\\\\")}"}""").isError shouldBe true

        // 승인 없이 온 쓰기는 하지 않는다
        val write = """{"path":"b.txt","content":"x"}"""
        client.run("write_file", write).summary shouldBe ClientWorkspace.REFUSED
        Files.exists(root.resolve("b.txt")) shouldBe false

        // 승인한 인자와 다르면 하지 않는다 (서버가 승인받은 뒤 바꿔치기)
        client.seen(approval("r1", "write_file", RiskLevel.WRITE, write))
        client.decided("r1", ApprovalDecision.Approve)
        client.run("write_file", """{"path":"evil.txt","content":"x"}""").summary shouldBe ClientWorkspace.REFUSED
        client.run("write_file", write).isError shouldBe false
        Files.readString(root.resolve("b.txt")) shouldBe "x"
        // 한 번 승인은 한 번만
        client.run("write_file", write).summary shouldBe ClientWorkspace.REFUSED

        // 거부한 것은 하지 않는다
        client.seen(approval("r2", "write_file", RiskLevel.WRITE, """{"path":"c.txt","content":"y"}"""))
        client.decided("r2", ApprovalDecision.Deny())
        client.run("write_file", """{"path":"c.txt","content":"y"}""").summary shouldBe ClientWorkspace.REFUSED

        // 셸: 사용자가 고친 명령으로만
        client.seen(approval("r3", "run_shell", RiskLevel.EXEC, """{"command":"echo one","cwd":"."}"""))
        client.decided("r3", ApprovalDecision.ApproveEdited(buildJsonObject { put("command", JsonPrimitive("echo two")) }))
        client.run("run_shell", """{"command":"echo one","cwd":"."}""").summary shouldBe ClientWorkspace.REFUSED
        client.run("run_shell", """{"command":"echo two","cwd":"."}""").output shouldContain "two"
    }

    test("쓰기 자동 승인은 서버가 준 경로가 아니라 실행 인자로 판단한다 (RC-07)") {
        val root = Files.createTempDirectory("vesta-auto").toRealPath()
        fun event(arguments: String, targets: List<String>) = AgentEvent.ApprovalRequested(
            "r", "write_file", RiskLevel.WRITE, ApprovalDetails("t", "", targets = targets), null, arguments = arguments,
        )
        AutoApprove.allows(event("""{"path":"src/App.tsx","content":"x"}""", listOf("src/App.tsx")), root.toString()) shouldBe true
        // 설명은 소스 파일인데 실제 인자는 git 훅
        AutoApprove.allows(event("""{"path":".git/hooks/pre-commit","content":"x"}""", listOf("src/App.tsx")), root.toString()) shouldBe false
        AutoApprove.allows(event("""{"path":"~/x.kt","content":"x"}""", emptyList()), root.toString()) shouldBe false
        AutoApprove.allows(event("""{"path":"../other/x.kt","content":"x"}""", emptyList()), root.toString()) shouldBe false
    }
})
