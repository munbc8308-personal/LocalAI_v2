package dev.vesta.tools

import dev.vesta.core.RiskLevel
import dev.vesta.core.tools.ApprovalDetails
import dev.vesta.core.tools.Tool
import dev.vesta.core.tools.ToolContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * 코드 수정안(unified diff) 적용 (PROJECT.md D-06). 쓰기 도구라 승인 후 실행한다.
 * `git apply --check`로 먼저 검사하고, 통과하면 적용한다. 저장소는 허용 폴더 안이어야 한다.
 */
class ApplyPatchTool(private val sandbox: PathSandbox) : Tool {
    override val spec = ToolSpec(
        "apply_patch",
        "git 저장소에 unified diff 패치를 적용한다. 사용자 승인이 필요하다. 먼저 git apply --check로 검사한다.",
        schema(
            "repo" to prop("string", "저장소 루트 폴더"),
            "patch" to prop("string", "unified diff (--- a/파일 +++ b/파일 형식)"),
            required = listOf("repo", "patch"),
        ),
    )
    override val risk = RiskLevel.WRITE

    override fun describe(arguments: JsonObject): ApprovalDetails {
        val patch = arguments.string("patch").orEmpty()
        val files = FILE.findAll(patch).map { it.groupValues[1] }.distinct().toList()
        val added = patch.lines().count { it.startsWith("+") && !it.startsWith("+++") }
        val removed = patch.lines().count { it.startsWith("-") && !it.startsWith("---") }
        val repo = (sandbox.check(arguments.string("repo").orEmpty()) as? PathSandbox.Check.Ok)?.path
        // 패치가 건드리는 파일 경로 (/dev/null은 새 파일·지운 파일 표시). 하나라도 허용 폴더 밖이면 대상을 비운다
        val targets = repo?.let { root -> files.filter { it != "/dev/null" }.map { (sandbox.check(root.resolve(it).toString()) as? PathSandbox.Check.Ok)?.path?.toString() } }
        return ApprovalDetails(
            title = "패치 적용: ${arguments.string("repo")} · 파일 ${files.size}개 (+$added/-$removed) ${files.take(3).joinToString()}",
            body = patch.take(4_000) + if (patch.length > 4_000) "\n… (${patch.length}자 중 4,000자만 표시)" else "",
            targets = if (targets == null || null in targets) emptyList() else targets.filterNotNull(),
        )
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val repo = when (val c = sandbox.check(arguments.string("repo") ?: return@withContext fail("repo가 필요함"))) {
            is PathSandbox.Check.Denied -> return@withContext fail(c.reason)
            is PathSandbox.Check.Ok -> c.path
        }
        if (!repo.resolve(".git").isDirectory()) return@withContext fail("git 저장소가 아님: ${sandbox.display(repo)}")
        val patch = arguments.string("patch")?.let { if (it.endsWith("\n")) it else "$it\n" } ?: return@withContext fail("patch가 필요함")
        // 패치가 저장소 밖 경로를 건드리지 못하게 (git apply도 막지만 미리 확인)
        if (FILE.findAll(patch).any { m -> m.groupValues[1].let { it.startsWith("/") || ".." in it.split('/') } }) {
            return@withContext fail("저장소 밖 경로를 바꾸는 패치는 적용하지 않음")
        }
        val file = Files.createTempFile("vesta-", ".patch").also { it.writeText(patch) }
        try {
            git(repo, "apply", "--check", file.toString()).let { (code, out) -> if (code != 0) return@withContext fail("패치 검사 실패:\n$out") }
            val (code, out) = git(repo, "apply", file.toString())
            if (code != 0) fail("적용 실패:\n$out")
            else ToolResult("적용함: ${sandbox.display(repo)}\n" + git(repo, "diff", "--stat").second, summary = "패치 적용됨")
        } finally {
            file.deleteIfExists()
        }
    }

    private fun git(repo: java.nio.file.Path, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); return -1 to "시간 초과" }
        return process.exitValue() to out.take(4_000)
    }

    private companion object {
        val FILE = Regex("^(?:\\+\\+\\+|---) (?:[ab]/)?(\\S+)", RegexOption.MULTILINE)
    }
}
