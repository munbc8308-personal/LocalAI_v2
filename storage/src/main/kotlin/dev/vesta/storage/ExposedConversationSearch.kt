package dev.vesta.storage

import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import dev.vesta.core.session.ConversationSearch
import dev.vesta.core.session.PastHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/**
 * 지난 대화 찾기 (M30 H-01·H-02): `messages_fts`(trigram)에 낱말마다 LIKE를 걸어 맞은 메시지를 모은다.
 * 3글자 이상은 trigram 색인을 쓰고, 2글자는 색인 안에서 훑는다. 본인 대화만, 모델 없이 답한 명령 턴(`policy`)은 뺀다
 */
class ExposedConversationSearch(private val db: Database, private val perTerm: Int = 400) : ConversationSearch {
    private data class Row(val id: Long, val session: String, val role: String, val content: String, val title: String?)

    override suspend fun search(user: UserId, terms: List<String>, limit: Int, exclude: SessionId?): List<PastHit> = withContext(Dispatchers.IO) {
        val words = terms.map { it.trim().lowercase() }.filter { it.length >= 2 }.distinct().take(MAX_TERMS)
        if (words.isEmpty()) return@withContext emptyList()
        transaction(db) {
            val rows = mutableMapOf<Long, Row>()
            val hits = mutableMapOf<Long, Int>()
            for (word in words) {
                val pattern = "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                exec(
                    """
                    SELECT m.id, m.session_id, m.role, m.content, s.title FROM messages_fts f
                    JOIN messages m ON m.id = f.rowid JOIN sessions s ON s.id = m.session_id
                    WHERE f.content LIKE ? ESCAPE '\' AND s.user_id = ? AND s.id <> ? AND (m.model IS NULL OR m.model <> 'policy')
                    ORDER BY m.id DESC LIMIT ?
                    """.trimIndent(),
                    listOf(TextColumnType() to pattern, LongColumnType() to user.value, TextColumnType() to (exclude?.value ?: ""), LongColumnType() to perTerm.toLong()),
                ) { rs ->
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        if (COMMAND.containsMatchIn(rs.getString(4))) continue
                        rows.getOrPut(id) { Row(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }
                        hits[id] = (hits[id] ?: 0) + 1
                    }
                }
            }
            // 대화마다 가장 잘 맞은(같으면 최근) 메시지 하나
            val best = rows.values.groupBy { it.session }.values
                .map { list -> list.maxWith(compareBy<Row>({ hits.getValue(it.id) }, { it.id })) }
                .sortedWith(compareByDescending<Row> { hits.getValue(it.id) }.thenByDescending { it.id })
                .take(limit)
            // 시각은 Exposed로 읽는다 (저장 꼴을 직접 풀지 않게)
            val times = Messages.select(Messages.id, Messages.createdAt).where { Messages.id inList best.map { it.id } }
                .associate { it[Messages.id].value to it[Messages.createdAt] }
            best.map { row ->
                // 짝: 질문이면 바로 뒤 답, 답이면 바로 앞 질문
                val partner = exec(
                    if (row.role == "USER") "SELECT content FROM messages WHERE session_id = ? AND id > ? AND role = 'ASSISTANT' ORDER BY id LIMIT 1"
                    else "SELECT content FROM messages WHERE session_id = ? AND id < ? AND role = 'USER' ORDER BY id DESC LIMIT 1",
                    listOf(TextColumnType() to row.session, LongColumnType() to row.id),
                ) { rs -> if (rs.next()) rs.getString(1) else null }
                PastHit(
                    session = SessionId(row.session),
                    title = row.title,
                    at = times[row.id] ?: Instant.EPOCH,
                    question = if (row.role == "USER") row.content else partner,
                    answer = if (row.role == "USER") partner else row.content,
                    snippet = snippet(row.content, words),
                    matched = hits.getValue(row.id),
                )
            }
        }
    }

    companion object {
        const val MAX_TERMS = 6
        /** 기억·예약·검색 명령 질문은 찾지 않는다 (그 답은 policy라 이미 빠진다) */
        private val COMMAND = Regex("^\\s*[/!](recall|remember|forget|memory|memories|schedule|schedules|skills|skillify)\\b", RegexOption.IGNORE_CASE)

        /** 맞은 낱말 앞뒤 [radius]자 */
        fun snippet(text: String, words: List<String>, radius: Int = 60): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            val at = words.map { flat.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val start = (at - radius).coerceAtLeast(0)
            val end = (at + radius * 2).coerceAtMost(flat.length)
            return (if (start > 0) "…" else "") + flat.substring(start, end) + if (end < flat.length) "…" else ""
        }
    }
}
