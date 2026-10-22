package dev.vesta.evolution

import dev.vesta.core.config.EvolutionConfig

/** 평가할 후보 모델 (E-02) */
data class Candidate(val name: String, val digest: String, val sizeBytes: Long, val family: String, val reason: String)

/**
 * 후보 고르기. 계열마다 태그를 크기(`31b`, `e4b`…)로 묶고, 묶음마다 설정의 양자화 순서([EvolutionConfig.quantizations])에서
 * 가장 앞에 있는 태그 하나를 고른다. 너무 크거나 작은 모델, 지금 모델, 이미 평가한 태그(같은 digest)는 뺀다.
 * 지금 모델과 같은 계열을 먼저, 그다음 큰 모델 순으로 [EvolutionConfig.maxCandidates]개까지
 */
object CandidatePicker {
    /** 이보다 작은 모델은 메인 모델로 쓰기에 품질이 모자라다 (M0 벤치마크) */
    const val MIN_BYTES = 8_000_000_000L

    fun pick(
        config: EvolutionConfig,
        current: String,
        tags: Map<String, List<CatalogTag>>,
        seen: (CatalogTag) -> Boolean,
    ): List<Candidate> {
        val currentFamily = current.substringBefore(':')
        val maxBytes = config.maxCandidateGigabytes * 1_000_000_000L
        return tags.flatMap { (family, list) ->
            list.filter { ':' in it.name && "cloud" !in it.name.substringAfter(':') }
                .groupBy { it.name.substringAfter(':').substringBefore('-') }
                .mapNotNull { (_, group) ->
                    config.quantizations.firstNotNullOfOrNull { q -> group.firstOrNull { it.name.endsWith("-$q") } }
                }
                .filter { it.sizeBytes in MIN_BYTES..maxBytes && it.name != current && !seen(it) }
                .map { Candidate(it.name, it.digest, it.sizeBytes, family, if (family == currentFamily) "지금 모델과 같은 계열" else "새 계열") }
        }.sortedWith(compareBy<Candidate> { it.family != currentFamily }.thenByDescending { it.sizeBytes })
            .take(config.maxCandidates)
    }

    /** "새로 나온 모델"에서 볼 계열: 도구 호출을 지원하고, 이름이 지켜보는 계열과 겹치지 않는 것 */
    fun newFamilies(config: EvolutionConfig, newest: List<CatalogFamily>): List<String> =
        if (!config.discoverNew) emptyList()
        else newest.filter { "tools" in it.capabilities && it.name !in config.families }.map { it.name }.take(NEW_FAMILIES)

    private const val NEW_FAMILIES = 5
}
