package dev.vesta.tools

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * 파일 도구·셸이 접근할 수 있는 경로 제한 (PROJECT.md T-04).
 * - 허용 폴더 안만 (심볼릭 링크를 풀어 실제 경로로 판정 → 링크로 빠져나가기 방지)
 * - 허용 폴더 안이라도 비밀 파일(.env, 키, SSH·키체인 폴더, Vesta 자신의 DB·설정)은 막는다
 */
class PathSandbox(roots: List<String>, private val home: Path = Path(System.getProperty("user.home"))) {
    val roots: List<Path> = roots.map { expand(it) }.map { real(it) }.distinct()

    private val deniedDirs = listOf(".ssh", ".gnupg", ".aws", ".kube", ".docker", ".vesta", "Library/Keychains")
        .map { home.resolve(it).normalize() }

    sealed interface Check {
        data class Ok(val path: Path) : Check
        data class Denied(val reason: String) : Check
    }

    /** 사용자가 준 경로(`~`, 상대 경로 포함)를 실제 경로로 바꿔 검사한다. 상대 경로는 첫 허용 폴더 기준. */
    fun check(raw: String): Check {
        if (roots.isEmpty()) return Check.Denied("허용된 폴더가 없음 (설정 tools.allowedPaths)")
        val expanded = expand(raw).let { if (it.isAbsolute) it else roots.first().resolve(it) }.normalize()
        val resolved = real(expanded)
        if (roots.none { resolved.startsWith(it) }) {
            return Check.Denied("허용된 폴더 밖: $resolved (허용: ${roots.joinToString { display(it) }})")
        }
        if (deniedDirs.any { resolved.startsWith(it) }) return Check.Denied("보호된 폴더: ${display(resolved)}")
        if (isSecretFile(resolved)) return Check.Denied("비밀 정보가 들어 있을 수 있는 파일: ${resolved.name}")
        return Check.Ok(resolved)
    }

    fun display(path: Path): String = path.toString().replaceFirst(home.toString(), "~")

    /** 있으면 링크를 푼 실제 경로, 없으면 가장 가까운 있는 부모를 풀고 나머지를 붙인다 (새 파일 경로용). */
    private fun real(path: Path): Path {
        val absolute = path.absolute().normalize()
        var existing = absolute
        val missing = ArrayDeque<String>()
        while (!existing.exists() && existing.parent != null) {
            missing.addFirst(existing.name)
            existing = existing.parent
        }
        val base = runCatching { existing.toRealPath() }.getOrDefault(existing)
        return missing.fold(base) { acc, part -> acc.resolve(part) }.normalize()
    }

    private fun expand(raw: String): Path = when {
        raw == "~" -> home
        raw.startsWith("~/") -> home.resolve(raw.removePrefix("~/"))
        else -> Path(raw)
    }

    companion object {
        private val secretNames = Regex("^(\\.env(\\..*)?|id_(rsa|ed25519|ecdsa|dsa)(\\.pub)?|.*\\.(pem|key|p12|pfx|keystore|jks)|\\.netrc|\\.npmrc|\\.pypirc|credentials(\\.json)?)$", RegexOption.IGNORE_CASE)

        fun isSecretFile(path: Path): Boolean = secretNames.matches(path.name)
    }
}
