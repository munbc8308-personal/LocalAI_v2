package dev.vesta.inference

import dev.vesta.core.ModelRole
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.config.ModelsConfig
import dev.vesta.core.model.ModelNotConfiguredException
import dev.vesta.core.model.ModelProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * 역할별 모델 배정과 메모리 예산 관리 (PROJECT.md 2.3, M-02·M-03).
 * 새 모델을 올릴 때 예산을 넘으면, 상시 모델(keepAlive: forever)이 아닌 것 중 가장 오래 안 쓴 모델부터 내린다.
 */
class ModelManager(
    private val models: ModelsConfig,
    private val budgetBytes: Long,
    private val runtime: ModelRuntime,
    private val clock: Clock = Clock.systemUTC(),
    /** Ollama 밖에서 쓰는 메모리 (미디어 워커의 음성 인식 모델 등, M12). 예산 계산에 더한다 */
    private val externalBytes: suspend () -> Long = { 0L },
) : ModelProvider {
    private val mutex = Mutex()
    private val lastUsed = mutableMapOf<String, Instant>()

    fun spec(role: ModelRole): ModelSpec? = when (role) {
        ModelRole.GENERAL -> models.general
        ModelRole.CODE -> models.code
        ModelRole.EMBEDDING -> models.embedding
        ModelRole.RERANKER -> models.reranker
        ModelRole.VISION -> models.vision
        ModelRole.IMAGE -> models.image
        ModelRole.STT -> models.stt
        ModelRole.TTS -> models.tts
    }

    override suspend fun acquire(role: ModelRole): ModelSpec {
        val spec = spec(role) ?: throw ModelNotConfiguredException(role)
        if (spec.engine != "ollama") return spec // 다른 엔진(mflux 등)은 각자 메모리를 관리한다
        mutex.withLock {
            runCatching { ensureRoom(spec.name) }
                .onFailure { log.warn(it) { "메모리 예산 확인 실패, 그대로 진행: ${spec.name}" } }
            lastUsed[spec.name] = clock.instant()
        }
        return spec
    }

    /**
     * Ollama 밖에서 [bytes]만큼 쓸 모델(이미지 생성 등)을 올리기 전에 자리를 만든다 (PROJECT.md 2.3).
     * 예산을 넘으면 상시 모델이 아닌 것 중 가장 오래 안 쓴 Ollama 모델부터 내린다.
     */
    suspend fun reserve(bytes: Long) = mutex.withLock {
        runCatching { makeRoom(runtime.loadedModels(), bytes) }
            .onFailure { log.warn(it) { "메모리 예산 확인 실패, 그대로 진행" } }
        Unit
    }

    /** [model]을 올릴 자리를 만든다. 이미 올라가 있으면 아무것도 하지 않는다. */
    private suspend fun ensureRoom(model: String) {
        val loaded = runtime.loadedModels()
        if (loaded.any { sameModel(it.name, model) }) return
        val needed = runtime.localModelSizes().entries.firstOrNull { sameModel(it.key, model) }?.value ?: 0
        makeRoom(loaded, needed)
    }

    private suspend fun makeRoom(loaded: List<LoadedModel>, needed: Long) {
        var used = loaded.sumOf { it.sizeBytes } + runCatching { externalBytes() }.getOrDefault(0L)
        val candidates = loaded
            .filterNot { loadedModel -> alwaysOnNames.any { sameModel(loadedModel.name, it) } }
            .sortedBy { lastUsed[it.name] ?: lastUsed.entries.firstOrNull { e -> sameModel(e.key, it.name) }?.value ?: Instant.EPOCH }
            .toMutableList()
        while (used + needed > budgetBytes && candidates.isNotEmpty()) {
            val victim = candidates.removeAt(0)
            log.info { "메모리 예산 초과 → 언로드: ${victim.name} (${victim.sizeBytes} bytes)" }
            runtime.unload(victim.name)
            used -= victim.sizeBytes
        }
        if (used + needed > budgetBytes) {
            log.warn { "상시 모델만으로 예산이 부족합니다: 사용 $used + 필요 $needed > 예산 $budgetBytes" }
        }
    }

    private val alwaysOnNames: List<String>
        get() = ModelRole.entries.mapNotNull { spec(it) }.filter { it.alwaysOn }.map { it.name }

    /** Ollama는 태그 없는 이름에 `:latest`를 붙인다. */
    private fun sameModel(a: String, b: String) = normalize(a) == normalize(b)
    private fun normalize(name: String) = if (':' in name) name else "$name:latest"
}
