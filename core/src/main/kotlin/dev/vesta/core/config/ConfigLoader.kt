package dev.vesta.core.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.parsers.PropsPropertySource
import com.sksamuel.hoplite.addPathSource
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists

object ConfigLoader {
    /**
     * 설정 파일이 있으면 읽고, 없으면 기본값을 쓴다. 형식이 틀리면 예외를 던진다.
     * [tunedFile](주간 자가 개선이 바꾼 값, M16)이 있으면 그 값이 설정 파일보다 우선한다. 없는 키는 설정 파일 값 그대로
     */
    @OptIn(ExperimentalHoplite::class)
    fun load(configFile: Path, tunedFile: Path? = configFile.resolveSibling("tuned.properties")): VestaConfig {
        val tuned = tunedFile?.let(TunedSettings::read).orEmpty()
        if (!configFile.exists() && tuned.isEmpty()) return VestaConfig()
        return ConfigLoaderBuilder.default()
            .withExplicitSealedTypes()
            .apply { if (tuned.isNotEmpty()) addPropertySource(PropsPropertySource(Properties().apply { putAll(tuned) }, "tuned.properties")) }
            .apply { if (configFile.exists()) addPathSource(configFile) }
            .build()
            .loadConfigOrThrow<VestaConfig>()
    }

    /** `vesta config init`이 쓰는 기본 설정 템플릿. */
    fun defaultTemplate(): String =
        checkNotNull(ConfigLoader::class.java.getResource("/default-config.yaml")) { "default-config.yaml 없음" }
            .readText()
}

/**
 * 주간 자가 개선이 바꾼 설정 (`~/.vesta/tuned.properties`). 점 표기 키 → 값.
 * 사람이 고쳐도 되지만 보통은 `vesta evolve approve`·`rollback`이 쓴다
 */
object TunedSettings {
    /** 바꿀 수 있는 키. 나머지는 쓰지 않는다 (잘못 쓴 키로 설정이 깨지지 않게) */
    val KEYS = setOf(
        "models.general.name",
        "models.code.name",
        "decision.thresholds.agent",
        "decision.thresholds.codeSufficient",
    )

    fun read(file: Path): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val props = Properties().apply { java.nio.file.Files.newBufferedReader(file).use(::load) }
        return props.stringPropertyNames().filter { it in KEYS }.associateWith { props.getProperty(it) }
    }

    /** [changes]의 값이 null이면 그 키를 지운다. 임시 파일에 쓰고 바꿔 넣어 반쯤 쓴 파일이 남지 않게 한다 */
    fun update(file: Path, changes: Map<String, String?>) {
        require(changes.keys.all { it in KEYS }) { "바꿀 수 없는 설정: ${changes.keys - KEYS}" }
        val next = read(file).toMutableMap()
        changes.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        java.nio.file.Files.createDirectories(file.toAbsolutePath().parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        java.nio.file.Files.newBufferedWriter(temp).use { out ->
            out.write("# Vesta 주간 자가 개선이 바꾼 설정 (PROJECT.md 7.13). config.yaml보다 우선한다. 되돌리기: vesta evolve rollback\n")
            next.toSortedMap().forEach { (key, value) -> out.write("$key=${value.replace("\\", "\\\\")}\n") }
        }
        java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
