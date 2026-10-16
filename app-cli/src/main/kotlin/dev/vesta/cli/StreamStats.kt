package dev.vesta.cli

import dev.vesta.core.inference.ChatChunk
import kotlinx.coroutines.flow.Flow
import kotlin.time.TimeSource

/** 스트리밍 응답 하나의 시간·토큰 통계. */
internal data class StreamStats(
    val text: String,
    val ttftMs: Long,
    val totalMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int,
    val decodeTokensPerSecond: Double,
) {
    companion object {
        suspend fun collect(stream: Flow<ChatChunk>, clock: TimeSource = TimeSource.Monotonic): StreamStats {
            val start = clock.markNow()
            var firstTokenAt: Long? = null
            var deltas = 0
            var done: ChatChunk.Done? = null
            val text = StringBuilder()
            stream.collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> {
                        if (firstTokenAt == null) firstTokenAt = start.elapsedNow().inWholeMilliseconds
                        deltas++
                        text.append(chunk.text)
                    }
                    is ChatChunk.Done -> done = chunk
                }
            }
            val totalMs = start.elapsedNow().inWholeMilliseconds
            val ttft = firstTokenAt ?: totalMs
            // 엔진이 사용량을 주지 않으면 스트림 조각 수로 근사한다
            val completionTokens = done?.usage?.completionTokens ?: deltas
            val decodeMs = (totalMs - ttft).coerceAtLeast(1)
            val tokensAfterFirst = (completionTokens - 1).coerceAtLeast(0)
            return StreamStats(
                text = text.toString(),
                ttftMs = ttft,
                totalMs = totalMs,
                promptTokens = done?.usage?.promptTokens,
                completionTokens = completionTokens,
                decodeTokensPerSecond = tokensAfterFirst * 1000.0 / decodeMs,
            )
        }
    }
}
