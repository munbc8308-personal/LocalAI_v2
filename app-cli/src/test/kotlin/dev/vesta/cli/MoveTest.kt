package dev.vesta.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class MoveTest : FunSpec({
    test("모델 저장소: 받은 모델·API로 만든 모델 매니페스트를 모두 찾고, 가리키는 blob을 읽는다") {
        val home = Files.createTempDirectory("store")
        val store = ModelStore.all(home).single { it.key == "ollama" }
        val pulled = home.resolve(".ollama/models/manifests/registry.ollama.ai/library/bge-m3/567m")
        val created = home.resolve(".ollama/models/manifests-v2/ollama.com/library/bge-m3-vesta/r5")
        listOf(pulled, created).forEach { Files.createDirectories(it.parent) }
        Files.writeString(pulled, """{"config":{"digest":"sha256:c1"},"layers":[{"digest":"sha256:w1"},{"digest":"sha256:t1"}]}""")
        Files.writeString(created, """{"config":{"digest":"sha256:c2"},"layers":[{"digest":"sha256:w2"},{"digest":"sha256:t1"}]}""")
        store.manifest("bge-m3:567m") shouldBe pulled
        store.manifest("bge-m3-vesta:r5") shouldBe created
        // 기본 모델에 없는 것만 묶음에 넣는다
        (store.blobs(created) - store.blobs(pulled)) shouldBe setOf("sha256-c2", "sha256-w2")
        store.blobs(home.resolve("없음")) shouldBe emptySet()
    }
})
