package dev.vesta.cli

import dev.vesta.core.inference.ChatChunk
import kotlinx.coroutines.flow.Flow
import kotlin.time.TimeSource

/**
 * 스트리밍 응답 하나의 시간·토큰 통계.
 * 추론 모델은 답변 전에 생각 토큰을 내므로, 첫 토큰(생각 포함)과 첫 답변 토큰을 따로 잰다.
 */
internal data class StreamStats(
    val text: String,
    val reasoning: String,
    /** 첫 토큰까지 (생각 토큰 포함). 엔진 속도 지표. */
    val ttftMs: Long,
    /** 첫 답변 토큰까지. 사용자가 체감하는 대기 시간. */
    val firstAnswerMs: Long,
    val totalMs: Long,
    val promptTokens: Int?,
    /** 생각 토큰 포함 전체 생성 토큰. */
    val completionTokens: Int,
    val reasoningChunks: Int,
    val decodeTokensPerSecond: Double,
) {
    companion object {
        suspend fun collect(stream: Flow<ChatChunk>, clock: TimeSource = TimeSource.Monotonic): StreamStats {
            val start = clock.markNow()
            var firstTokenAt: Long? = null
            var firstAnswerAt: Long? = null
            var chunks = 0
            var reasoningChunks = 0
            var done: ChatChunk.Done? = null
            val text = StringBuilder()
            val reasoning = StringBuilder()
            stream.collect { chunk ->
                when (chunk) {
                    is ChatChunk.Reasoning -> {
                        if (firstTokenAt == null) firstTokenAt = start.elapsedNow().inWholeMilliseconds
                        chunks++
                        reasoningChunks++
                        reasoning.append(chunk.text)
                    }
                    is ChatChunk.Delta -> {
                        val now = start.elapsedNow().inWholeMilliseconds
                        if (firstTokenAt == null) firstTokenAt = now
                        if (firstAnswerAt == null) firstAnswerAt = now
                        chunks++
                        text.append(chunk.text)
                    }
                    is ChatChunk.Done -> done = chunk
                }
            }
            val totalMs = start.elapsedNow().inWholeMilliseconds
            val ttft = firstTokenAt ?: totalMs
            // 엔진이 사용량을 주지 않으면 스트림 조각 수로 근사한다
            val completionTokens = done?.usage?.completionTokens ?: chunks
            val decodeMs = (totalMs - ttft).coerceAtLeast(1)
            val tokensAfterFirst = (completionTokens - 1).coerceAtLeast(0)
            return StreamStats(
                text = text.toString(),
                reasoning = reasoning.toString(),
                ttftMs = ttft,
                firstAnswerMs = firstAnswerAt ?: totalMs,
                totalMs = totalMs,
                promptTokens = done?.usage?.promptTokens,
                completionTokens = completionTokens,
                reasoningChunks = reasoningChunks,
                decodeTokensPerSecond = tokensAfterFirst * 1000.0 / decodeMs,
            )
        }
    }
}
