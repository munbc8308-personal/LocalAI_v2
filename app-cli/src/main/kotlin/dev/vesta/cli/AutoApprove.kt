package dev.vesta.cli

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import java.nio.file.Path

/**
 * 파일 쓰기 자동 승인 범위 (`--accept-writes`·`/accept`, PROJECT.md W-06, M17).
 * 내장 파일 도구(write_file·make_directory·move_file)가 **작업 폴더 안의 소스·문서 파일**에 쓸 때만 묻지 않는다.
 * - 허용 목록: 확장자가 [SAFE_EXTENSIONS]인 파일 (폴더 만들기는 이름만 검사)
 * - 그래도 막는 것: 보호 폴더([PROTECTED_DIRS]: git·CI·편집기·에이전트 설정), 빌드·설치 때 돌아가는 파일([PROTECTED_FILE])
 * - 비교는 대소문자를 무시한다 (macOS 파일 시스템은 `.GIT/hooks`도 `.git/hooks`다)
 * - 패치(apply_patch)는 이름 바꾸기·바이너리·모드 변경처럼 미리 다 알 수 없는 경로를 건드릴 수 있어 늘 묻는다
 * 나머지(셸, MCP 도구, 작업 폴더 밖)도 늘 묻는다. 지침이나 읽은 파일 속 지시문이 실행되는 코드를 몰래 바꾸지 못하게 한다
 */
internal object AutoApprove {
    private val TOOLS = setOf("write_file", "make_directory", "move_file")
    private val PROTECTED_DIRS = setOf(
        ".git", ".github", ".gitlab", ".circleci", ".buildkite", ".husky", ".vscode", ".idea", ".devcontainer",
        ".claude", ".cursor", ".vesta", ".mvn", "gradle", "node_modules",
    )
    private val SAFE_EXTENSIONS = setOf(
        "md", "txt", "rst", "adoc", "kt", "java", "scala", "groovy", "swift", "go", "rs", "py", "rb", "php", "c", "h", "cc", "cpp", "hpp", "m", "mm", "cs",
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "css", "scss", "less", "html", "json", "yaml", "yml", "toml", "xml", "sql", "csv",
        "properties", "proto", "graphql", "svg",
    )
    /** 확장자가 안전해도 빌드·설치·테스트 도구가 스스로 읽어 실행하는 파일 */
    private val PROTECTED_FILE = Regex(
        "^(package\\.json|package-lock\\.json|pom\\.xml|setup\\.py|setup\\.cfg|pyproject\\.toml|conftest\\.py|Cargo\\.toml|build\\.rs|" +
            "\\.gitlab-ci\\.ya?ml|\\.travis\\.ya?ml|azure-pipelines\\.ya?ml|bitbucket-pipelines\\.ya?ml|docker-compose\\.ya?ml|compose\\.ya?ml|" +
            "\\.pre-commit-config\\.ya?ml|.*\\.config\\.(js|ts|mjs|cjs)|VESTA\\.md|CLAUDE\\.md|AGENTS\\.md)$",
        RegexOption.IGNORE_CASE,
    )

    fun allows(event: AgentEvent.ApprovalRequested, workspace: String?): Boolean {
        if (workspace == null || event.risk != RiskLevel.WRITE || event.tool !in TOOLS) return false
        val root = runCatching { Path.of(workspace).toRealPath() }.getOrNull() ?: return false
        val targets = event.details.targets
        return targets.isNotEmpty() && targets.all { target ->
            val path = Path.of(target).normalize()
            val name = path.fileName?.toString() ?: return@all false
            val fileOk = event.tool == "make_directory" || name.substringAfterLast('.', "").lowercase() in SAFE_EXTENSIONS
            path.startsWith(root) && path != root && fileOk &&
                root.relativize(path).none { it.toString().lowercase() in PROTECTED_DIRS } &&
                !PROTECTED_FILE.matches(name)
        }
    }
}
