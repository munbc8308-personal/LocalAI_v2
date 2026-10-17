package dev.vesta.retrieval

/**
 * 질문을 FTS5 MATCH 식으로 바꾼다.
 * 한국어는 "회의록은"처럼 조사가 붙으므로 흔한 조사를 떼고 접두어 검색(`"회의록"*`)으로 찾는다.
 */
object KeywordQuery {
    private val particles = listOf(
        "에서는", "으로는", "에게서", "이라고", "라고", "에서", "으로", "에게", "까지", "부터", "보다", "처럼", "이랑", "하고",
        "은", "는", "이", "가", "을", "를", "에", "의", "로", "와", "과", "도", "만", "랑",
    )
    private val stopwords = setOf(
        "뭐야", "뭔가", "무엇", "어떻게", "어디", "언제", "왜", "알려줘", "알려", "줘", "해줘", "설명", "설명해", "대해", "관련",
        "그리고", "그런데", "있어", "있나", "있는", "하는", "하나", "the", "a", "an", "is", "are", "what", "how", "of", "to", "in",
    )
    private val token = Regex("[\\p{L}\\p{N}_.\\-]+")

    fun terms(question: String): List<String> = token.findAll(question.lowercase())
        .map { stripParticle(it.value.trim('.', '-')) }
        .filter { it.length >= 2 && it !in stopwords }
        .distinct()
        .toList()

    /** 검색어가 없으면 빈 문자열 (키워드 검색을 건너뛴다). */
    fun match(question: String): String = terms(question).joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }

    private fun stripParticle(word: String): String {
        if (!word.any { it in '가'..'힣' }) return word
        val particle = particles.firstOrNull { word.endsWith(it) && word.length - it.length >= 2 } ?: return word
        return word.dropLast(particle.length)
    }
}
