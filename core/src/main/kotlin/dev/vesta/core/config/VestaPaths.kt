package dev.vesta.core.config

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Vesta 데이터 경로 (PROJECT.md 1.1a). 기본은 `~/.vesta/`.
 * 테스트나 별도 환경에서는 환경 변수 `VESTA_HOME`으로 바꾼다.
 */
class VestaPaths(val home: Path) {
    val configFile: Path get() = home.resolve("config.yaml")
    val database: Path get() = home.resolve("vesta.db")
    val logs: Path get() = home.resolve("logs")
    val bench: Path get() = home.resolve("bench")

    companion object {
        fun default(env: Map<String, String> = System.getenv()): VestaPaths {
            val home = env["VESTA_HOME"]?.let { Path(it) }
                ?: Path(System.getProperty("user.home"), ".vesta")
            return VestaPaths(home)
        }
    }
}
