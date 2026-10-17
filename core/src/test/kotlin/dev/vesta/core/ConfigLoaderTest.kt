package dev.vesta.core

import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaConfig
import dev.vesta.core.config.VestaPaths
import dev.vesta.core.config.WebMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class ConfigLoaderTest : FunSpec({
    test("설정 파일이 없으면 기본값") {
        val dir = createTempDirectory("vesta")
        ConfigLoader.load(dir.resolve("config.yaml")) shouldBe VestaConfig()
    }

    test("기본 설정 템플릿을 읽을 수 있다") {
        val dir = createTempDirectory("vesta")
        val file = dir.resolve("config.yaml")
        file.writeText(javaClass.getResource("/default-config.yaml")!!.readText())
        val config = ConfigLoader.load(file)
        config.decision.model shouldBe "winnow:e4b"
        config.web.mode shouldBe WebMode.AUTO
        config.mcp.servers.keys shouldBe setOf("orchard")
    }

    test("일부 값만 덮어쓰기") {
        val dir = createTempDirectory("vesta")
        val file = dir.resolve("config.yaml")
        file.writeText(
            """
            models:
              general: { name: "qwen-test", keepAlive: forever }
            memory:
              budgetPercent: 60
            """.trimIndent(),
        )
        val config = ConfigLoader.load(file)
        config.models.general?.name shouldBe "qwen-test"
        config.memory.budgetPercent shouldBe 60
        config.decision.baseUrl shouldBe "http://127.0.0.1:11435"
    }

    test("VESTA_HOME으로 경로 변경") {
        VestaPaths.default(mapOf("VESTA_HOME" to "/tmp/v")).configFile.toString() shouldBe "/tmp/v/config.yaml"
    }
})
