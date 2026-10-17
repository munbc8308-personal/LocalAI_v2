package dev.vesta.cli

/**
 * 한국어 답변 품질 검사 (M0 벤치마크에서 발견한 문제, PROJECT.md 13.1).
 * - 한자(중국어) 섞임: "轻型 동시성" 같은 경우
 * - 조사 앞 띄어쓰기: "MLX 은", "GPU 와" 같은 경우
 */
internal object KoreanStyle {
    private val han = Regex("[\\u4E00-\\u9FFF]+")
    private val spacedParticle = Regex("[A-Za-z0-9)] (은|는|이|가|을|를|와|과|의|로|으로|에|에서|도)(?=[\\s.,!?])")

    fun hanWords(text: String): List<String> = han.findAll(text).map { it.value }.toList()
    fun spacedParticles(text: String): Int = spacedParticle.findAll(text).count()
}
