package dev.vesta.training

import dev.vesta.core.UserId
import dev.vesta.core.training.Passage
import dev.vesta.core.training.PassageSource
import dev.vesta.core.training.SampleKind
import dev.vesta.core.training.SampleSignal
import dev.vesta.core.training.SampleStatus
import dev.vesta.core.training.TrainingSample
import dev.vesta.core.training.TrainingSampleRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * 검색 재료 만들기 (PROJECT.md L-23): 조각 하나를 메인 모델에게 보여 주고 "이 조각이 답하는 질문"을 만든 뒤 검증한다.
 * - D6(결정 모델)으로 그 조각이 질문에 답하는지 본다. 약하면 버린다 (지어낸 질문)
 * - 지금 임베딩으로 질문을 검색해, 가깝지만 답하지 못하는 조각은 어려운 오답으로 같이 남긴다
 * - 가까운 조각이 같은 질문에 답하면 오답으로 쓰지 않는다 (정답이 여럿. 코드는 같은 기능의 다른 파일·테스트가 자주 그렇다, 첫 실행 20개 중 14개).
 *   같은 파일의 이웃 조각은 아예 보지 않는다
 * 버린 조각도 REJECTED로 남겨 다음에 다시 묻지 않는다. 확실한 것(D6 [AUTO_ACCEPT] 이상)만 바로 쓰고 나머지는 검토 대기(L-26)
 */
class PairMiner(
    private val passages: PassageSource,
    private val samples: TrainingSampleRepository,
    /** 메인 모델: 조각 → 질문 한 줄. 못 만들면 null */
    private val ask: suspend (Passage) -> String?,
    /** D6: 글이 질문에 답하는가 (0~1) */
    private val sufficient: suspend (String, String) -> Double,
) {
    /** 조각을 [limit]개까지 본다. [busy]가 참이 되면 그 자리에서 멈춘다 */
    suspend fun run(users: Set<UserId>, limit: Int, busy: suspend () -> Boolean = { false }): List<TrainingSample> {
        if (users.isEmpty()) return emptyList()
        val used = samples.messageIds(SampleKind.RETRIEVAL)
        val added = mutableListOf<TrainingSample>()
        for (passage in passages.sample(users, used, limit)) {
            if (busy()) break
            val sample = runCatching { mine(passage) }.onFailure { log.warn { "검색 재료 실패 (조각 ${passage.chunkId}): ${it.message}" } }.getOrNull() ?: continue
            samples.add(sample)?.let { added += it }
        }
        return added
    }

    private suspend fun mine(passage: Passage): TrainingSample? {
        val question = ask(passage)?.let(::clean)
        if (question == null) return rejected(passage, "(질문 없음)", "질문을 만들지 못함")
        val score = sufficient(question, passage.passage)
        if (score < MIN_SUFFICIENT) return rejected(passage, question, "D6 %.2f: 이 조각으로 답하지 못함".format(score))
        val near = passages.nearest(passage, question, NEIGHBORS)
        val rank = near.indexOfFirst { it.chunkId == passage.chunkId }.let { if (it < 0) null else it + 1 }
        val negatives = mutableListOf<Long>()
        val alsoAnswers = mutableListOf<String>()
        for (other in near.filter { it.chunkId != passage.chunkId && it.path != passage.path }.take(CHECK_NEGATIVES)) {
            val s = sufficient(question, other.passage)
            if (s >= MIN_SUFFICIENT) alsoAnswers += other.path
            else if (s < MAX_NEGATIVE && negatives.size < NEGATIVES) negatives += other.chunkId
        }
        return TrainingSample(
            id = 0, userId = passage.userId, kind = SampleKind.RETRIEVAL, signal = SampleSignal.SYNTHETIC, messageId = passage.chunkId,
            question = question, answer = PrivacyFilter.clean(passage.text).take(4_000),
            label = negatives.joinToString(",").ifEmpty { "-" }, confidence = score,
            status = if (score >= AUTO_ACCEPT) SampleStatus.ACCEPTED else SampleStatus.PENDING,
            note = "${passage.path}${passage.heading?.let { " > $it" }.orEmpty()} · 지금 검색 순위 ${rank ?: "${NEIGHBORS}위 밖"}" +
                alsoAnswers.takeIf { it.isNotEmpty() }?.let { " · 다른 조각도 답함: ${it.distinct().joinToString()}" }.orEmpty(),
        )
    }

    private fun rejected(passage: Passage, question: String, why: String) = TrainingSample(
        id = 0, userId = passage.userId, kind = SampleKind.RETRIEVAL, signal = SampleSignal.SYNTHETIC, messageId = passage.chunkId,
        question = question, answer = null, label = "-", confidence = 0.0, status = SampleStatus.REJECTED, note = why,
    )

    companion object {
        const val AUTO_ACCEPT = 0.9
        const val MIN_SUFFICIENT = 0.8
        const val MAX_NEGATIVE = 0.3
        const val NEIGHBORS = 10
        const val CHECK_NEGATIVES = 5
        const val NEGATIVES = 3

        /** 첫 줄만, 번호·따옴표·"질문:" 떼고, 개인정보 지움. 너무 짧거나 길면 null */
        fun clean(raw: String): String? {
            val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val text = line.replace(Regex("^(?:[-*•]|\\d+[.)]|질문\\s*:|Q\\s*:)\\s*"), "").trim('"', '\'', '“', '”', '`').trim()
            return PrivacyFilter.clean(text).takeIf { it.length in 6..200 }
        }
    }
}
