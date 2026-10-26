package dev.vesta.cli

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import java.nio.file.Path

/**
 * 파일 쓰기 자동 승인 범위 (`--accept-writes`·`/accept`, PROJECT.md WS-06, M17).
 * 원칙: **작업 폴더 안의 소스 코드·문서 파일**만 묻지 않는다. 막을 이름을 하나씩 늘리는 대신 허용할 것만 정한다.
 * - 도구: write_file·make_directory·move_file. 패치(apply_patch)는 미리 다 알 수 없는 경로(이름 바꾸기·바이너리·모드)가 있어 늘 묻는다
 * - 파일: 확장자가 [SOURCE_EXTENSIONS]일 때만. 설정 형식(json·yaml·toml·xml·properties)은 도구가 읽어 실행하는 경우가 많아 늘 묻는다
 * - 경로의 어느 부분이든 점으로 시작하면(`.git`, `.github`, `.envrc`, `.cargo`…) 묻는다. [PROTECTED_DIRS]도 묻는다
 * - 이름이 빌드·작업 도구 설정 꼴이면(`*.config.*`, `*rc`, `gulpfile.js`, `conftest.py`, `setup.py`…) 묻는다
 * - 비교는 대소문자를 무시한다 (macOS 파일 시스템은 `.GIT/hooks`도 `.git/hooks`다)
 * 남는 위험: 소스 파일은 빌드·실행하면 결국 돈다. 자동 승인은 사용자가 켜는 것이고, 셸 실행(빌드·테스트)은 늘 따로 묻는다.
 * 셸·MCP 도구·작업 폴더 밖도 늘 묻는다. 지침이나 읽은 파일 속 지시문이 실행되는 코드를 몰래 바꾸지 못하게 한다
 */
internal object AutoApprove {
    private val TOOLS = setOf("write_file", "make_directory", "move_file")
    private val PROTECTED_DIRS = setOf("gradle", "buildsrc", "project", "node_modules", "vendor", "scripts", "bin", "hooks")
    private val SOURCE_EXTENSIONS = setOf(
        "md", "txt", "rst", "adoc", "kt", "java", "scala", "groovy", "swift", "go", "rs", "py", "rb", "php", "c", "h", "cc", "cpp", "hpp", "m", "mm", "cs",
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "css", "scss", "less", "html", "sql", "csv", "proto", "graphql", "svg",
    )
    /** 확장자가 소스라도 빌드·작업·테스트 도구가 스스로 찾아 실행하는 이름 */
    private val PROTECTED_FILE = Regex(
        "^(.*\\.config\\.[a-z]+|.*\\.conf\\.[a-z]+|.*rc(\\.[a-z]+)?|Package\\.swift|(site|user)customize\\.py|Jenkinsfile.*|Dangerfile.*|Fastfile|Podfile|Brewfile|(gulpfile|gruntfile|jakefile|noxfile|tasks|dodo|fabfile|toxfile|conftest|setup|manage|build|" +
            "webpack|rollup|vite|babel|jest|vitest|eslint|prettier|tailwind|postcss|next|nuxt|svelte|astro)\\.[a-z]+|" +
            "VESTA\\.md|CLAUDE\\.md|AGENTS\\.md|GEMINI\\.md)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 셸 자동 승인 (`--accept-shell`·`/accept shell`, 사용자가 켤 때만): 셸 도구(run_shell)만. 차단 목록에 걸린 명령은 서버가 승인과 관계없이 실행하지 않는다.
     * 다른 사용자의 셸(주인에게 넘어오는 승인)·MCP 도구는 여기로 오지 않거나 셸이 아니라 해당 없음
     */
    fun shell(event: AgentEvent.ApprovalRequested): Boolean = event.tool == "run_shell" && event.risk == RiskLevel.EXEC

    fun allows(event: AgentEvent.ApprovalRequested, workspace: String?): Boolean {
        if (workspace == null || event.risk != RiskLevel.WRITE || event.tool !in TOOLS) return false
        val root = runCatching { Path.of(workspace).toRealPath() }.getOrNull() ?: return false
        val targets = event.details.targets
        return targets.isNotEmpty() && targets.all { target ->
            val path = Path.of(target).normalize()
            val name = path.fileName?.toString() ?: return@all false
            val fileOk = if (event.tool == "make_directory") true else name.substringAfterLast('.', "").lowercase() in SOURCE_EXTENSIONS && !PROTECTED_FILE.matches(name)
            path.startsWith(root) && path != root && fileOk &&
                root.relativize(path).none { part -> part.toString().let { it.startsWith(".") || it.lowercase() in PROTECTED_DIRS } }
        }
    }
}
