package dev.vesta.cli

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import java.nio.file.Path

/**
 * 파일 쓰기 자동 승인 범위 (`--accept-writes`·`/accept`, PROJECT.md W-06, M17).
 * 내장 파일 도구가 **작업 폴더 안의 일반 파일**에 쓸 때만 묻지 않는다. 나머지(셸, MCP 도구, 작업 폴더 밖,
 * 실행되는 파일: git 훅·CI 설정·빌드 스크립트·셸 스크립트 등)는 계속 묻는다.
 * 지침(VESTA.md)이나 읽은 파일 속 지시문이 모델을 움직여도 실행되는 코드를 몰래 바꾸지 못하게 한다
 */
internal object AutoApprove {
    private val TOOLS = setOf("write_file", "make_directory", "move_file", "apply_patch")
    /** 경로 중간에 있으면 자동 승인하지 않는 폴더 */
    private val PROTECTED_DIRS = setOf(".git", ".github", ".gitlab", ".circleci", ".husky", ".vscode", ".idea", ".devcontainer", "gradle")
    /** 실행되거나 빌드·설치 때 돌아가는 파일 */
    private val PROTECTED_FILE = Regex(
        "^(.*\\.(sh|bash|zsh|fish|command|ps1|bat|cmd|gradle|gradle\\.kts|plist|service)|gradlew|mvnw|Makefile|GNUmakefile|Dockerfile|" +
            "Containerfile|docker-compose\\.ya?ml|compose\\.ya?ml|package\\.json|pom\\.xml|setup\\.py|setup\\.cfg|pyproject\\.toml|" +
            "Cargo\\.toml|build\\.rs|Gemfile|Rakefile|\\.envrc|\\.npmrc|\\.pre-commit-config\\.ya?ml|\\.gitattributes|\\.gitmodules|" +
            "VESTA\\.md|CLAUDE\\.md|AGENTS\\.md)$",
        RegexOption.IGNORE_CASE,
    )

    fun allows(event: AgentEvent.ApprovalRequested, workspace: String?): Boolean {
        if (workspace == null || event.risk != RiskLevel.WRITE || event.tool !in TOOLS) return false
        val root = runCatching { Path.of(workspace).toRealPath() }.getOrNull() ?: return false
        val targets = event.details.targets
        return targets.isNotEmpty() && targets.all { target ->
            val path = Path.of(target).normalize()
            path.startsWith(root) && path != root &&
                root.relativize(path).none { it.toString() in PROTECTED_DIRS } &&
                !PROTECTED_FILE.matches(path.fileName.toString())
        }
    }
}
