package dev.vesta.core.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addPathSource
import java.nio.file.Path
import kotlin.io.path.exists

object ConfigLoader {
    /** 설정 파일이 있으면 읽고, 없으면 기본값을 쓴다. 형식이 틀리면 예외를 던진다. */
    @OptIn(ExperimentalHoplite::class)
    fun load(configFile: Path): VestaConfig {
        if (!configFile.exists()) return VestaConfig()
        return ConfigLoaderBuilder.default()
            .withExplicitSealedTypes()
            .addPathSource(configFile)
            .build()
            .loadConfigOrThrow<VestaConfig>()
    }

    /** `vesta config init`이 쓰는 기본 설정 템플릿. */
    fun defaultTemplate(): String =
        checkNotNull(ConfigLoader::class.java.getResource("/default-config.yaml")) { "default-config.yaml 없음" }
            .readText()
}
