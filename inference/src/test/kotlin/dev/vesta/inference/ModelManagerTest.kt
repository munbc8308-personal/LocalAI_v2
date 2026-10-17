package dev.vesta.inference

import dev.vesta.core.ModelRole
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.config.ModelsConfig
import dev.vesta.core.model.ModelNotConfiguredException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private const val GB = 1L shl 30

private class FakeRuntime(sizes: Map<String, Long>, loaded: List<String> = emptyList()) : ModelRuntime {
    val sizes = sizes.toMutableMap()
    val loaded = loaded.toMutableList()
    val unloaded = mutableListOf<String>()

    override suspend fun loadedModels() = loaded.map { LoadedModel(it, sizes.getValue(it), sizes.getValue(it)) }
    override suspend fun localModelSizes() = sizes.toMap()
    override suspend fun unload(model: String) {
        loaded.remove(model)
        unloaded += model
    }
}

class ModelManagerTest : FunSpec({
    val config = ModelsConfig(
        general = ModelSpec("main:a", keepAlive = "forever"),
        code = ModelSpec("coder:b", keepAlive = "30m"),
        vision = ModelSpec("vision:c", keepAlive = "30m"),
    )

    test("예산 안이면 아무것도 내리지 않는다") {
        val runtime = FakeRuntime(mapOf("main:a" to 40 * GB, "coder:b" to 20 * GB), loaded = listOf("main:a"))
        ModelManager(config, 89 * GB, runtime).acquire(ModelRole.CODE).name shouldBe "coder:b"
        runtime.unloaded.shouldBeEmpty()
    }

    test("예산을 넘으면 상시 모델이 아닌 것 중 오래 안 쓴 모델부터 내린다") {
        val runtime = FakeRuntime(
            mapOf("main:a" to 40 * GB, "coder:b" to 20 * GB, "vision:c" to 20 * GB),
            loaded = listOf("main:a"),
        )
        val manager = ModelManager(config, 70 * GB, runtime)
        manager.acquire(ModelRole.CODE)
        runtime.loaded += "coder:b"
        manager.acquire(ModelRole.VISION) // 40 + 20 + 20 > 70 → coder 내림 (main은 상시)
        runtime.unloaded shouldContainExactly listOf("coder:b")
    }

    test("이미 올라가 있는 모델은 그대로") {
        val runtime = FakeRuntime(mapOf("main:a" to 90 * GB), loaded = listOf("main:a"))
        ModelManager(config, 10 * GB, runtime).acquire(ModelRole.GENERAL)
        runtime.unloaded.shouldBeEmpty()
    }

    test("태그 없는 이름은 :latest와 같은 모델로 본다") {
        val runtime = FakeRuntime(mapOf("plain:latest" to 5 * GB), loaded = listOf("plain:latest"))
        ModelManager(ModelsConfig(general = ModelSpec("plain")), 1 * GB, runtime).acquire(ModelRole.GENERAL)
        runtime.unloaded.shouldBeEmpty()
    }

    test("설정 안 된 역할은 안내 메시지와 함께 실패") {
        shouldThrow<ModelNotConfiguredException> {
            ModelManager(ModelsConfig(), 89 * GB, FakeRuntime(emptyMap())).acquire(ModelRole.GENERAL)
        }
    }
})
