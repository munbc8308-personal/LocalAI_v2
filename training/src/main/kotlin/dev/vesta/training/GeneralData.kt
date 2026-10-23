package dev.vesta.training

import dev.vesta.core.training.CuratorTurn
import dev.vesta.core.training.TrainingSample
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** 메인 모델 학습에 필요한 것 (L-24): 앱이 넘겨 준다 */
class GeneralHooks(
    /** 답 메시지 ID → 그 턴 (어느 에이전트가 답했는지 보려고) */
    val turn: suspend (Long) -> CuratorTurn?,
    /** 그 시각의 일반 대화 시스템 프롬프트 (Prompts.base) */
    val system: (Instant) -> String,
    /** 지금 쓰는 메인 모델 (학습 출발점) */
    val current: () -> String,
)

/**
 * 메인 모델 SFT 재료 (L-24): 👍 받은 답(ANSWER/good, 검토 거침) 중 **일반 대화(CHAT)가 근거·도구 없이 답한 것만**.
 * 문서·웹·코드 답은 근거를 본 뒤의 답이라, 근거 없이 질문만 보고 배우면 지어내기를 배운다. 질문·답은 이미 개인정보를 지운 글
 */
object GeneralData {
    data class Row(val system: String, val user: String, val answer: String)

    suspend fun rows(samples: List<TrainingSample>, hooks: GeneralHooks): List<Row> = samples
        .filter { it.label == "good" && !it.answer.isNullOrBlank() && it.messageId != null }
        .mapNotNull { s ->
            val turn = hooks.turn(s.messageId!!) ?: return@mapNotNull null
            if (turn.answerAgent != "CHAT") return@mapNotNull null
            Row(hooks.system(turn.questionAt), s.question, s.answer!!)
        }
        .distinctBy { it.user }

    fun write(rows: List<Row>, target: Path): Path {
        Files.writeString(target, rows.joinToString("\n") { buildJsonObject { put("system", it.system); put("user", it.user); put("answer", it.answer) }.toString() } + "\n")
        return target
    }
}
