package dev.vesta.memory

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.memory.Memory
import dev.vesta.core.memory.MemoryKind
import dev.vesta.core.memory.MemoryRepository
import dev.vesta.core.memory.MemorySource
import kotlin.math.sqrt

/** 임베딩: 글들 → (모델 이름, 벡터들) */
typealias Embedder = suspend (List<String>) -> Pair<String, List<FloatArray>>

/**
 * 장기 기억 (PROJECT.md 7.21, M27). 사용자별 사실 문장을 저장하고, 질문과 비슷한 것을 꺼낸다.
 * - 직접 말하기(MEM-02)는 모델 없이 바로 저장한다
 * - 비슷한 기억(유사도 [SAME] 이상)이 있으면 새 문장으로 바꾼다: 같은 사실이 쌓이지 않고, 바뀐 사실("이사했어")이 옛것을 덮는다
 * - 민감한 꼴은 저장하지 않는다 (MEM-06)
 * - 임베딩 모델이 바뀌면 꺼낼 때 옛 벡터를 다시 만든다
 */
class MemoryService(
    private val repo: MemoryRepository,
    private val embed: Embedder,
    private val maxPerUser: Int = 500,
) {
    sealed interface Saved {
        data class New(val memory: Memory) : Saved
        data class Replaced(val memory: Memory, val before: String) : Saved
        data class Refused(val reason: String) : Saved
    }

    suspend fun remember(user: UserId, text: String, source: MemorySource, kind: MemoryKind? = null): Saved {
        val clean = clean(text)
        if (clean.length < 2) return Saved.Refused("기억할 내용이 없습니다")
        sensitive(clean)?.let { return Saved.Refused("$it 같은 민감한 정보는 기억하지 않습니다") }
        val (model, vectors) = embed(listOf(clean))
        val vector = vectors.single()
        val stored = fresh(user, model)
        val best = stored.maxByOrNull { cosine(it.second, vector) }?.takeIf { cosine(it.second, vector) >= SAME }
        if (best != null) {
            val before = best.first
            repo.update(user, before.id, clean, kind ?: guessKind(clean).takeIf { source == MemorySource.EXPLICIT }, vector, model)
            return Saved.Replaced(before.copy(text = clean), before.text)
        }
        if (stored.size >= maxPerUser) {
            // 오래 안 쓴 자동 기억부터 비운다. 직접 말한 것은 지우지 않는다
            val oldest = stored.map { it.first }.filter { it.source == MemorySource.AUTO }.minByOrNull { it.lastUsedAt ?: it.updatedAt }
                ?: return Saved.Refused("기억이 ${maxPerUser}개로 가득 찼습니다. 웹 UI 설정 > 기억에서 정리해 주세요")
            repo.delete(user, oldest.id)
        }
        return Saved.New(repo.add(user, clean, kind ?: guessKind(clean), source, vector, model))
    }

    /**
     * 가장 비슷한 기억 하나를 지운다. 짧은 말("녹차 좋아하는 거")은 유사도가 높지 않아(실측 0.52~0.86),
     * [FORGET] 이상이면서 두 번째보다 [FORGET_MARGIN] 이상 높을 때만 지운다: 헷갈리면 지우지 않고 번호로 지우라고 안내한다
     */
    suspend fun forget(user: UserId, query: String): Memory? {
        val (model, vectors) = embed(listOf(clean(query)))
        val ranked = fresh(user, model).map { it.first to cosine(it.second, vectors.single()) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull()?.takeIf { it.second >= FORGET } ?: return null
        if (ranked.size > 1 && best.second - ranked[1].second < FORGET_MARGIN) return null
        repo.delete(user, best.first.id)
        return best.first
    }

    /**
     * 질문과 관련 있을 수 있는 기억 (MEM-04). 임베딩 유사도는 짧은 문장끼리 관련·무관이 겹쳐(실측 관련 0.38~0.57, 무관 0.31~0.43)
     * 문턱을 낮게 두고 넉넉히 넣는다: 쓸지는 메인 모델이 정한다(프롬프트에 "관계없으면 꺼내지 않는다").
     * 고른 것은 ID 순으로 돌려준다: 질문마다 순서가 바뀌면 시스템 프롬프트 앞부분이 달라져 캐시를 못 쓴다.
     * 쓴 것은 마지막 쓴 때·횟수를 남긴다
     */
    suspend fun recall(user: UserId, question: String, limit: Int = 12, minimum: Double = RECALL): List<Memory> {
        if (question.isBlank()) return emptyList()
        val all = repo.stored(user)
        if (all.isEmpty()) return emptyList()
        val (model, vectors) = embed(listOf(clean(question)))
        val found = fresh(user, model, all).map { it.first to cosine(it.second, vectors.single()) }
            .filter { it.second >= minimum }.sortedByDescending { it.second }.take(limit).map { it.first }.sortedBy { it.id }
        repo.touch(found.map { it.id })
        return found
    }

    suspend fun list(user: UserId): List<Memory> = repo.list(user)

    suspend fun delete(user: UserId, id: Long): Boolean = repo.delete(user, id)

    suspend fun clear(user: UserId): Int = repo.clear(user)

    /** 글 고치기 (웹 UI·CLI) */
    suspend fun edit(user: UserId, id: Long, text: String, kind: MemoryKind?): Saved {
        val clean = clean(text)
        if (clean.length < 2) return Saved.Refused("기억할 내용이 없습니다")
        sensitive(clean)?.let { return Saved.Refused("$it 같은 민감한 정보는 기억하지 않습니다") }
        val before = repo.list(user).firstOrNull { it.id == id } ?: return Saved.Refused("없는 기억입니다")
        val (model, vectors) = embed(listOf(clean))
        repo.update(user, id, clean, kind, vectors.single(), model)
        return Saved.Replaced(before.copy(text = clean, kind = kind ?: before.kind), before.text)
    }

    /**
     * "기억해: …"·`/remember`·"잊어: …"·`/forget`·`/memory` (MEM-02). 기억 명령이면 답할 글, 아니면 null.
     * 손님(GUEST)은 기억을 쓰지 않는다 (MEM-06)
     */
    suspend fun command(user: UserContext, text: String): String? {
        val command = MemoryCommand.parse(text) ?: return null
        if (user.role == UserRole.GUEST) return "손님 계정은 기억 기능을 쓰지 않습니다."
        return when (command) {
            is MemoryCommand.Remember -> when (val saved = remember(user.userId, command.text, MemorySource.EXPLICIT)) {
                is Saved.New -> "기억했어요: ${saved.memory.text}"
                is Saved.Replaced -> "기억을 바꿨어요: ${saved.before} → ${saved.memory.text}"
                is Saved.Refused -> saved.reason
            }
            is MemoryCommand.Forget -> {
                val index = command.text.trim().toIntOrNull()
                val removed = if (index != null) repo.list(user.userId).getOrNull(index - 1)?.also { repo.delete(user.userId, it.id) } else forget(user.userId, command.text)
                removed?.let { "잊었어요: ${it.text}" } ?: "그런 기억을 찾지 못했습니다. /memory 로 목록을 보고 번호로 지울 수 있어요 (/forget 3)."
            }
            MemoryCommand.Show -> {
                val all = repo.list(user.userId)
                if (all.isEmpty()) "아직 기억하는 것이 없습니다. \"기억해: 나는 커피보다 차를 좋아해\"처럼 말해 주세요."
                else buildString {
                    append("기억하는 것 ${all.size}개 (최근 바뀐 순):\n")
                    all.take(30).forEachIndexed { i, m -> append("${i + 1}. ${m.text}${if (m.source == MemorySource.AUTO) " (자동)" else ""}\n") }
                    if (all.size > 30) append("… 나머지는 웹 UI 설정 > 기억에서\n")
                    append("지우기: /forget 번호 또는 /forget 내용")
                }
            }
        }
    }

    /** 기억마다 지금 모델의 벡터. 옛 모델·벡터 없는 것은 다시 만들어 저장한다 */
    private suspend fun fresh(user: UserId, model: String, all: List<dev.vesta.core.memory.StoredMemory>? = null): List<Pair<Memory, FloatArray>> {
        val stored = all ?: repo.stored(user)
        val stale = stored.filter { it.vector == null || it.model != model }
        val redone = if (stale.isEmpty()) emptyMap() else {
            val (usedModel, vectors) = embed(stale.map { it.memory.text })
            stale.zip(vectors).associate { (s, v) -> repo.revector(s.memory.id, v, usedModel); s.memory.id to v }
        }
        return stored.mapNotNull { s -> (redone[s.memory.id] ?: s.vector)?.let { s.memory to it } }
    }

    companion object {
        /** 같은 사실로 보고 바꾸는 유사도 */
        const val SAME = 0.88
        /** "잊어: …"로 지울 만큼 비슷한 유사도와, 두 번째 후보와의 차이 */
        const val FORGET = 0.5
        const val FORGET_MARGIN = 0.07
        /** 답에 넣을 수 있는 유사도 (분명히 무관한 것만 뺀다) */
        const val RECALL = 0.30
        const val MAX_LENGTH = 200

        fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”', '\'').take(MAX_LENGTH)

        private val SENSITIVE = listOf(
            Regex("\\d{6}\\s*-\\s*[1-4]\\d{6}") to "주민등록번호",
            Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)") to "카드 번호",
            Regex("(비밀\\s*번호|패스워드|password|passwd|암호)\\s*(는|은|가|이|:|=)?\\s*\\S{3,}", RegexOption.IGNORE_CASE) to "비밀번호",
            Regex("\\b(sk-[A-Za-z0-9]{10,}|xox[abpr]-[A-Za-z0-9-]{10,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})") to "API 키",
            Regex("(?=[A-Za-z0-9_\\-]*\\d)(?=[A-Za-z0-9_\\-]*[A-Za-z])[A-Za-z0-9_\\-]{32,}") to "토큰",
        )

        /** 민감한 꼴이면 그 이름, 아니면 null */
        fun sensitive(text: String): String? = SENSITIVE.firstOrNull { it.first.containsMatchIn(text) }?.second

        fun guessKind(text: String): MemoryKind = when {
            Regex("좋아|싫어|선호|즐겨|싫|별로|말투|반말|존댓말|불러 ?줘|like|prefer|hate").containsMatchIn(text) -> MemoryKind.PREFERENCE
            Regex("프로젝트|개발|만들고|회사|직장|일해|일하|업무|공부|준비 ?중|진행").containsMatchIn(text) -> MemoryKind.PROJECT
            Regex("이름|살아|살고|사는|산다|거주|나이|생일|가족|아내|남편|아이|딸|아들|키우|알레르기|고향|출신").containsMatchIn(text) -> MemoryKind.PROFILE
            else -> MemoryKind.OTHER
        }

        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
        }
    }
}

/** 기억 명령 (MEM-02) */
sealed interface MemoryCommand {
    data class Remember(val text: String) : MemoryCommand
    data class Forget(val text: String) : MemoryCommand
    data object Show : MemoryCommand

    companion object {
        private val SLASH_REMEMBER = Regex("^[/!]remember\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SLASH_FORGET = Regex("^[/!]forget\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SLASH_LIST = Regex("^[/!](memory|memories)\\s*$", RegexOption.IGNORE_CASE)
        /** "기억해: …", "기억해 줘, …" */
        private val PREFIX_REMEMBER = Regex("^기억해\\s*(줘|둬|두어|주세요|놔)?\\s*[:：,]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
        /** "… 기억해 줘." (물음표로 끝나면 질문이라 아님) */
        private val SUFFIX_REMEMBER = Regex("^(.{2,}?)\\s*(이?라고|고|는 거|다는 거|을|를)?\\s*기억해\\s*(줘|둬|두어|주세요|놔)[.!~]*$", RegexOption.DOT_MATCHES_ALL)
        private val PREFIX_FORGET = Regex("^잊어\\s*(줘|버려|주세요)?\\s*[:：,]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
        private val SUFFIX_FORGET = Regex("^(.{2,}?)\\s*(은|는|을|를)?\\s*잊어\\s*(줘|버려|주세요)[.!~]*$", RegexOption.DOT_MATCHES_ALL)
        private val LIST_WORDS = Regex("^(내\\s*)?(기억|기억\\s*목록)\\s*(보여\\s*줘|알려\\s*줘)?[.!?]*$|^뭘\\s*기억하고\\s*있어\\??$|^나에 ?대해 ?(뭘|무엇을)\\s*기억(하고 ?있어|해)\\??$")

        fun parse(text: String): MemoryCommand? {
            val t = text.trim()
            SLASH_LIST.find(t)?.let { return Show }
            SLASH_REMEMBER.find(t)?.let { return Remember(it.groupValues[1]) }
            SLASH_FORGET.find(t)?.let { return Forget(it.groupValues[1]) }
            PREFIX_REMEMBER.find(t)?.let { return Remember(it.groupValues[2]) }
            PREFIX_FORGET.find(t)?.let { return Forget(it.groupValues[2]) }
            if (t.endsWith("?")) return if (LIST_WORDS.matches(t)) Show else null
            SUFFIX_REMEMBER.find(t)?.let { return Remember(it.groupValues[1]) }
            SUFFIX_FORGET.find(t)?.let { return Forget(it.groupValues[1]) }
            if (LIST_WORDS.matches(t)) return Show
            return null
        }
    }
}
