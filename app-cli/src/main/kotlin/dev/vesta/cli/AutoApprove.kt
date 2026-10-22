package dev.vesta.cli

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import java.nio.file.Path

/**
 * 파일 쓰기 자동 승인 범위 (`--accept-writes`·`/accept`, PROJECT.md W-06, M17).
 * 원칙: **작업 폴더 안의 소스 코드·문서 파일**만 묻지 않는다. 막을 이름을 하나씩 늘리는 대신 허용할 것만 정한다.
 * - 도구: write_file·make_directory·move_file. 패치(apply_patch)는 미리 다 알 수 없는 경로(이름 바꾸기·바이너리·모드)가 있어 늘 묻는다
 * - 파일: 확장자가 [SOURCE_EXTENSIONS]일 때만. 설정 형식(json·yaml·toml·xml·properties)은 도구가 읽어 실행하는 경우가 많아 늘 묻는다
 * - 경로의 어느 부분이든 점으로 시작하면(`.git`, `.github`, `.envrc`, `.cargo`…) 묻는다. [PROTECTED_DIRS]도 묻는다
 * - 이름이 빌드·작업 도구 설정 꼴이면(`*.config.*`, `*rc`, `gulpfile.js`, `conftest.py`, `setup.py`…) 묻는다
 * - 비교는 대소문자를 무시한다 (macOS 파일 시스템은 `.GIT/hooks`도 `.git/hooks`다)
 * 셸·MCP 도구·작업 폴더 밖도 늘 묻는다. 지침이나 읽은 파일 속 지시문이 실행되는 코드를 몰래 바꾸지 못하게 한다
 */
internal object AutoApprove {
    private val TOOLS = setOf("write_file", "make_directory", "move_file")
    private val PROTECTED_DIRS = setOf("gradle", "node_modules", "vendor", "scripts", "bin", "hooks")
    private val SOURCE_EXTENSIONS = setOf(
        "md", "txt", "rst", "adoc", "kt", "java", "scala", "groovy", "swift", "go", "rs", "py", "rb", "php", "c", "h", "cc", "cpp", "hpp", "m", "mm", "cs",
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "css", "scss", "less", "html", "sql", "csv", "proto", "graphql", "svg",
    )
    /** 확장자가 소스라도 빌드·작업·테스트 도구가 스스로 찾아 실행하는 이름 */
    private val PROTECTED_FILE = Regex(
        "^(.*\\.config\\.[a-z]+|.*rc(\\.[a-z]+)?|(gulpfile|gruntfile|jakefile|noxfile|tasks|dodo|fabfile|toxfile|conftest|setup|manage|build|" +
            "webpack|rollup|vite|babel|jest|vitest|eslint|prettier|tailwind|postcss|next|nuxt|svelte|astro)\\.[a-z]+|" +
            "VESTA\\.md|CLAUDE\\.md|AGENTS\\.md|GEMINI\\.md)$",
        RegexOption.IGNORE_CASE,
    )

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
