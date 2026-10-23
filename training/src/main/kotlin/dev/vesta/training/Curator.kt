package dev.vesta.training

import dev.vesta.core.UserId
import dev.vesta.core.training.CuratorSource
import dev.vesta.core.training.CuratorTurn
import dev.vesta.core.training.SampleKind
import dev.vesta.core.training.SampleSignal
import dev.vesta.core.training.SampleStatus
import dev.vesta.core.training.TrainingSample
import dev.vesta.core.training.TrainingSampleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 말하지 않은 신호 찾기 (PROJECT.md L-21). 모델 없이 규칙으로, 빠르고 같은 입력에 같은 결과:
 * - 담당을 지정해 같은 질문을 다시 물음(`/web …`) → 앞 질문의 라우팅 재료 (그 담당이 맞았다)
 * - 말만 바꿔 다시 물음 → 앞 답이 나빴다 (약하게)
 * - "아니 그게 아니라"로 시작 → 앞 답이 나빴다
 * - 도구 승인 거부 → 그 도구 사용이 틀렸다
 * 👍·👎는 남길 때 바로 재료가 되므로 여기서 보지 않는다
 */
object SignalDetector {
    /** 이 확실함 이상만 바로 쓰고, 아래는 검토 대기 (L-26) */
    const val AUTO_ACCEPT = 0.9
    private val WINDOW: Duration = Duration.ofMinutes(10)
    private val EXPLICIT = Regex("^/(web|docs|code|task|chat)\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CORRECTION = Regex("^\\s*(아니[야요,.\\s]|아니\\s*그게|그게\\s*아니|틀렸|잘못\\s*(답|알)|그거\\s*말고|no[,.\\s]|wrong)", RegexOption.IGNORE_CASE)

    /** [after]보다 뒤의 질문이 만든 신호만 (앞 턴은 비교용) */
    fun detect(turns: List<CuratorTurn>, after: Long): List<TrainingSample> = turns.groupBy { it.sessionId }.values.flatMap { session ->
        val ordered = session.sortedBy { it.questionId }
        ordered.flatMapIndexed { i, turn ->
            if (turn.questionId <= after) return@flatMapIndexed emptyList()
            val found = mutableListOf<TrainingSample>()
            turn.deniedTools.distinct().forEach { tool ->
                found += sample(turn, SampleKind.TOOL, SampleSignal.DENIAL, turn.questionId, turn.question, null, "denied:$tool", 0.7, "승인 거부")
            }
            val prev = ordered.getOrNull(i - 1)?.takeIf { Duration.between(it.questionAt, turn.questionAt) <= WINDOW } ?: return@flatMapIndexed found
            val explicit = EXPLICIT.find(turn.question.trim())
            if (explicit != null) {
                val agent = explicit.groupValues[1].uppercase()
                val similarity = similarity(prev.question, explicit.groupValues[2])
                // 앞 질문을 라우터가 골랐고(명시 지정이 아니고) 다른 담당이 답했는데, 같은 질문을 이 담당으로 다시 물었다
                if (prev.routedStage != "EXPLICIT" && prev.answerAgent != null && prev.answerAgent != agent && similarity >= 0.5) {
                    found += sample(prev, SampleKind.ROUTING, SampleSignal.REASK, prev.questionId, prev.question, null, agent,
                        (0.6 + 0.4 * similarity).coerceAtMost(0.95), "다시 물음: ${turn.question.take(100)}")
                }
            } else if (prev.answerId != null && similarity(prev.question, turn.question) >= 0.6) {
                found += sample(prev, SampleKind.ANSWER, SampleSignal.REPHRASE, prev.answerId, prev.question, prev.answer, "bad", 0.5, "말을 바꿔 다시 물음: ${turn.question.take(100)}")
            }
            if (prev.answerId != null && CORRECTION.containsMatchIn(turn.question)) {
                found += sample(prev, SampleKind.ANSWER, SampleSignal.CORRECTION, prev.answerId, prev.question, prev.answer, "bad", 0.65, "바로잡음: ${turn.question.take(100)}")
            }
            found
        }
    }

    private fun sample(turn: CuratorTurn, kind: SampleKind, signal: SampleSignal, messageId: Long?, question: String, answer: String?, label: String, confidence: Double, note: String) =
        TrainingSample(
            id = 0, userId = turn.userId, kind = kind, signal = signal, messageId = messageId,
            question = PrivacyFilter.clean(question).take(2_000), answer = answer?.let { PrivacyFilter.clean(it).take(4_000) },
            label = label, confidence = confidence, status = if (confidence >= AUTO_ACCEPT) SampleStatus.ACCEPTED else SampleStatus.PENDING,
            note = PrivacyFilter.clean(note),
        )

    /** 글자 두 개씩 묶음의 겹침 (Dice: 조사가 붙어도 덜 깎인다). 띄어쓰기·문장 부호·대소문자는 보지 않는다 */
    fun similarity(a: String, b: String): Double {
        fun grams(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.windowed(2).toSet()
        val x = grams(a)
        val y = grams(b)
        if (x.isEmpty() || y.isEmpty()) return if (a.trim().equals(b.trim(), ignoreCase = true)) 1.0 else 0.0
        return 2.0 * (x intersect y).size / (x.size + y.size)
    }
}

/** 큐레이터가 한 번 돈 결과 */
data class CuratorReport(val turns: Int, val added: List<TrainingSample>, val stoppedEarly: Boolean)

/**
 * 학습 재료 큐레이터 (PROJECT.md L-25): 백그라운드에서 **쉬는 때만** 돈다 ([idle]: 요청·음성 대화·학습·주간 점검이 없음).
 * 동의한 사용자(L-09) 대화의 새 턴에서 신호를 찾아 재료로 넣는다(개인정보 지움, 중복 제외). 누가 쓰기 시작하면 바로 멈추고 다음에 이어 간다.
 * 확실한 재료는 바로, 나머지는 검토 대기(L-26)
 */
class Curator(
    private val source: CuratorSource,
    private val samples: TrainingSampleRepository,
    private val dataUsers: suspend () -> Set<UserId>,
    private val idle: suspend () -> Boolean,
    /** 한 일을 남긴다 (에이전트 스튜디오 타임라인, L-27). 새 턴이 있었을 때만 */
    private val record: suspend (CuratorReport) -> Unit = {},
    private val batch: Int = 500,
) {
    private var job: Job? = null

    /** 한 번 돈다. 쉬는 때가 아니거나 볼 것이 없으면 null */
    suspend fun runOnce(): CuratorReport? {
        if (!idle()) return null
        val users = dataUsers()
        if (users.isEmpty()) return null
        val cursor = samples.cursor()
        val turns = source.turns(cursor, users, batch)
        val fresh = turns.filter { it.questionId > cursor }
        if (fresh.isEmpty()) return null
        val found = SignalDetector.detect(turns, cursor)
        val added = mutableListOf<TrainingSample>()
        var stopped = false
        for (sample in found) {
            if (!idle()) { stopped = true; break }
            samples.add(sample)?.let { added += it }
        }
        // 멈췄으면 커서를 옮기지 않는다: 다음에 같은 턴을 다시 보고, 이미 넣은 것은 중복이라 빠진다
        if (!stopped) samples.setCursor(fresh.maxOf { it.questionId })
        val report = CuratorReport(fresh.size, added, stopped)
        runCatching { record(report) }.onFailure { log.warn { "큐레이터 기록 실패: ${it.message}" } }
        if (added.isNotEmpty()) log.info { "학습 재료 ${added.size}개 (검토 대기 ${added.count { it.status == SampleStatus.PENDING }})" }
        return report
    }

    fun start(scope: CoroutineScope, every: Duration = Duration.ofMinutes(10)) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(every.toMillis())
                runCatching { runOnce() }.onFailure { log.warn(it) { "큐레이터 실패" } }
            }
        }
    }
}
