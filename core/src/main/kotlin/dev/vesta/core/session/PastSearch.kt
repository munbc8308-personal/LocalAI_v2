package dev.vesta.core.session

import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import java.time.Instant

/**
 * 지난 대화에서 맞은 질문·답 한 쌍 (PROJECT.md H-02, M30). [matched]는 맞은 낱말 수, [snippet]은 맞은 부분 앞뒤
 */
data class PastHit(
    val session: SessionId,
    val title: String?,
    val at: Instant,
    val question: String?,
    val answer: String?,
    val snippet: String,
    val matched: Int,
)

/** 지난 대화 찾기 (H-01·H-02). 늘 [user] 본인 대화만 */
interface ConversationSearch {
    /** 낱말이 많이 맞을수록, 같으면 최근 것부터. 대화마다 가장 잘 맞은 한 쌍. [exclude]는 지금 대화 */
    suspend fun search(user: UserId, terms: List<String>, limit: Int = 10, exclude: SessionId? = null): List<PastHit>
}
