package dev.vesta.cli

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.tools.ApprovalDetails
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class AutoApproveTest : FunSpec({
    val root = createTempDirectory("ws").toRealPath()
    fun event(tool: String, vararg targets: String, risk: RiskLevel = RiskLevel.WRITE) =
        AgentEvent.ApprovalRequested("r", tool, risk, ApprovalDetails("t", "b", targets = targets.map { root.resolve(it).toString() }), null)

    test("작업 폴더 안 일반 파일을 내장 도구로 쓸 때만 자동 승인") {
        AutoApprove.allows(event("write_file", "src/Main.kt"), root.toString()) shouldBe true
        AutoApprove.allows(event("make_directory", "docs/new"), root.toString()) shouldBe true
        AutoApprove.allows(event("move_file", "a.txt", "b.txt"), root.toString()) shouldBe true
    }

    test("셸·MCP·대상 없음·작업 폴더 밖·작업 폴더 없음은 묻는다") {
        AutoApprove.allows(event("run_shell", "x", risk = RiskLevel.EXEC), root.toString()) shouldBe false
        AutoApprove.allows(event("mail_send", "x"), root.toString()) shouldBe false
        AutoApprove.allows(event("write_file"), root.toString()) shouldBe false
        AutoApprove.allows(AgentEvent.ApprovalRequested("r", "write_file", RiskLevel.WRITE, ApprovalDetails("t", "b", targets = listOf("/etc/hosts")), null), root.toString()) shouldBe false
        AutoApprove.allows(event("write_file", "../escape.txt"), root.toString()) shouldBe false
        AutoApprove.allows(event("write_file", "a.txt"), null) shouldBe false
    }

    test("실행되는 파일(git 훅·CI·빌드·셸 스크립트·지침 파일)은 묻는다") {
        listOf(
            ".git/hooks/pre-commit", ".github/workflows/ci.yml", "build.gradle.kts", "gradlew", "scripts/deploy.sh",
            "package.json", "Makefile", "VESTA.md", ".envrc", "gradle/libs.versions.toml",
        ).forEach { AutoApprove.allows(event("write_file", it), root.toString()) shouldBe false }
        // 대소문자를 바꿔도 같은 폴더다 (macOS)
        listOf(".GIT/hooks/pre-commit.md", ".GitHub/workflows/ci.yml", "Node_Modules/x.js", "WEBPACK.CONFIG.JS").forEach {
            AutoApprove.allows(event("write_file", it), root.toString()) shouldBe false
        }
        // 허용 목록 밖 확장자·확장자 없는 파일, 패치는 늘 묻는다
        listOf("run.command", "Dockerfile", "hook", "x.plist", "evil.sh.bak").forEach { AutoApprove.allows(event("write_file", it), root.toString()) shouldBe false }
        // 설정 형식·점 파일·작업 도구 설정 꼴 (3차 검토)
        listOf(
            "renovate.json", "Taskfile.yml", "lefthook.yml", "config/app.toml", ".drone.yml", ".woodpecker/ci.yaml", ".gitea/x.md", ".cargo/config.md",
            ".eslintrc.js", "gulpfile.js", "noxfile.py", "setup.py", "vite.config.ts", "tailwind.config.js", "src/.hidden.kt",
            "buildSrc/src/main/kotlin/Plugin.kt", "project/Build.scala", "Package.swift", "sitecustomize.py", "Jenkinsfile.groovy", "Dangerfile.js", "nginx.conf.js",
        ).forEach { AutoApprove.allows(event("write_file", it), root.toString()) shouldBe false }
        AutoApprove.allows(event("make_directory", ".github"), root.toString()) shouldBe false
        AutoApprove.allows(event("apply_patch", "README.md"), root.toString()) shouldBe false
        AutoApprove.allows(event("move_file", "a.txt", ".git/hooks/post-checkout"), root.toString()) shouldBe false
    }
})
