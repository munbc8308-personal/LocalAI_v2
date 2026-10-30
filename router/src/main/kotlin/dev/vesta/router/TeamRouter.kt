package dev.vesta.router

import dev.vesta.core.config.TeamRoutingConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** 자동 배정 후보 (M41 TR-01): 팀이면 [tools]는 멤버 도구 합. [texts]는 이름·설명과 예시 질문 */
data class TeamCandidate(val name: String, val team: Boolean, val texts: List<String>, val tools: Set<String>)

/** 고른 후보. [auto]면 바로 맡기고, 아니면 추천만 */
data class TeamPick(val name: String, val team: Boolean, val score: Double, val margin: Double, val auto: Boolean)

/**
 * 팀 자동 배정 (PROJECT.md 7.35, M41). 질문과 후보 글(설명·예시 질문)의 임베딩 유사도 중 가장 높은 것을 후보 점수로 보고,
 * 기본 에이전트·일상 질문 글(기준점)보다 [TeamRoutingConfig.autoMargin]만큼 높아야 맡긴다.
 * 예시 질문이 없으면 점수가 낮게 나와(실측 0.45~0.6) 맡기지 않고 추천에 그친다. 필요한 도구가 없는 후보는 뺀다(TR-03)
 */
class TeamRouter(
    private val model: suspend () -> String,
    private val embed: suspend (String, List<String>) -> List<FloatArray>,
    private val config: TeamRoutingConfig,
    private val anchors: List<String> = ANCHORS,
) {
    /** (모델, 글) → 벡터. 후보 글은 바뀔 때만 다시 만든다 */
    private val cache = ConcurrentHashMap<Pair<String, String>, FloatArray>()

    suspend fun pick(question: String, candidates: List<TeamCandidate>): TeamPick? {
        val text = question.trim()
        if (!config.enabled || text.length < config.minChars || candidates.isEmpty()) return null
        val needed = required(text)
        val usable = candidates.filter { c -> needed.all { prefix -> c.tools.any { it.startsWith(prefix) } } }
        if (usable.isEmpty()) return null
        val m = model()
        val q = embed(m, listOf(text)).single()
        val vectors = vectors(m, usable.flatMap { it.texts } + anchors)
        val anchor = anchors.maxOf { cosine(q, vectors.getValue(it)) }
        val (best, score) = usable.map { c -> c to c.texts.maxOf { cosine(q, vectors.getValue(it)) } }.maxBy { it.second }
        val margin = score - anchor
        return when {
            best.team && score >= config.auto && margin >= config.autoMargin -> TeamPick(best.name, true, score, margin, auto = true)
            score >= config.suggest && margin >= config.suggestMargin -> TeamPick(best.name, best.team, score, margin, auto = false)
            else -> null
        }
    }

    private suspend fun vectors(model: String, texts: List<String>): Map<String, FloatArray> {
        val missing = texts.distinct().filter { !cache.containsKey(model to it) }
        if (missing.isNotEmpty()) embed(model, missing).forEachIndexed { i, v -> cache[model to missing[i]] = v }
        // 지운 팀의 글이 쌓이지 않게
        if (cache.size > MAX_CACHE) cache.keys.retainAll(texts.map { model to it }.toSet())
        return texts.associateWith { cache.getValue(model to it) }
    }

    companion object {
        private const val MAX_CACHE = 2_000

        /** 팀이 아닌 질문의 기준점: 기본 에이전트 설명과 일상 질문 (실측으로 고름, 13.54) */
        val ANCHORS = listOf(
            "대화: 일반 대화·설명·글쓰기·번역",
            "웹 리서치: 웹을 검색해 출처와 함께 답한다",
            "코드 도우미: 등록한 저장소에서 찾아 파일·줄과 함께 답하고 수정안을 낸다",
            "개인 비서: 일정·메일·미리 알림 확인과 등록",
            "이미지: 그림을 만든다",
            "오늘 날씨 알려 줘", "이거 무슨 뜻이야? 설명해 줘", "맛집 추천해 줘", "영어로 번역해 줘",
            "그림 그려 줘", "이 코드 무슨 뜻이야", "요즘 뉴스 알려 줘",
        )

        /** 질문에 필요한 도구 (이름 앞부분). 그 도구가 없는 팀은 그 일을 할 수 없다 */
        private val NEEDS = listOf(
            Regex("구글 ?시트|스프레드시트|google ?sheets?", RegexOption.IGNORE_CASE) to "sheets_",
            Regex("지메일|gmail|구글 ?메일", RegexOption.IGNORE_CASE) to "gmail_",
            Regex("구글 ?문서|구글 ?독스|google ?docs", RegexOption.IGNORE_CASE) to "docs_",
            Regex("구글 ?드라이브|google ?drive", RegexOption.IGNORE_CASE) to "drive_",
            Regex("구글 ?캘린더|google ?calendar", RegexOption.IGNORE_CASE) to "gcal_",
            Regex("테스트(를)? ?(돌려|실행|통과)|빌드(해|하고|를|까지)|git |커밋") to "run_shell",
        )

        internal fun required(text: String): List<String> = NEEDS.filter { (regex, _) -> regex.containsMatchIn(text) }.map { it.second }

        internal fun cosine(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
        }
    }
}
