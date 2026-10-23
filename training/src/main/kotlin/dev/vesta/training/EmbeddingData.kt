package dev.vesta.training

import dev.vesta.core.UserId
import dev.vesta.core.training.PassageSource
import dev.vesta.core.training.TrainingSample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** 임베딩 학습에 필요한 것 (L-23): 앱이 넘겨 준다 */
class EmbeddingHooks(
    val passages: PassageSource,
    val ollamaUrl: String,
    /** 지금 쓰는 임베딩 모델 (학습한 버전이 켜져 있으면 그것) */
    val current: suspend () -> String,
    /** 모든 컬렉션의 벡터를 [model]로 다시 만든다 */
    val switch: suspend (model: String) -> Unit,
    /** 질의에 붙이는 형식 (EmbeddingProfile.query) */
    val query: (String) -> String = { it },
)

/**
 * 임베딩 학습·평가 파일 (L-23):
 * - pairs.jsonl: {"query", "positive", "negatives"} — 검토를 거친 검색 재료. 조각이 바뀌어(다시 색인) 없어진 것은 뺀다
 * - heldout.jsonl: 조각 ID 해시로 5분의 1을 떼어 학습에 쓰지 않는다 {"query", "id"}
 * - corpus.jsonl: 평가할 조각 전체 {"id", "text", "ref"} (지금 모델로 색인한 동의한 사용자의 컬렉션)
 * - eval.jsonl: 사람이 쓴 평가셋 {"query", "expected"} — 정답 위치가 corpus에 있는 것만
 */
object EmbeddingData {
    data class Files(val pairs: Path, val heldout: Path, val corpus: Path, val eval: Path, val train: Int, val held: Int)

    fun heldOut(chunkId: Long) = Math.floorMod(chunkId * 2654435761L, 5L) == 0L

    suspend fun write(dir: Path, samples: List<TrainingSample>, passages: PassageSource, users: Set<UserId>, model: String, evalFiles: List<Path>, query: (String) -> String = { it }): Files {
        val ids = samples.flatMap { s -> listOfNotNull(s.messageId) + negatives(s) }.distinct()
        val byId = passages.byIds(ids).associateBy { it.chunkId }
        val train = mutableListOf<String>()
        val held = mutableListOf<String>()
        for (s in samples) {
            val positive = byId[s.messageId ?: continue] ?: continue
            if (heldOut(positive.chunkId)) {
                held += buildJsonObject { put("query", query(s.question)); put("id", positive.chunkId) }.toString()
            } else {
                val negs = negatives(s).mapNotNull { byId[it]?.passage }
                train += buildJsonObject {
                    put("query", query(s.question)); put("positive", positive.passage); put("negatives", JsonArray(negs.map { JsonPrimitive(it) }))
                }.toString()
            }
        }
        val corpus = passages.corpus(users, model)
        val refs = corpus.map { ref(it.path, it.heading) }
        val evals = evalFiles.flatMap { CodeSufficiencyData.rows(it) }
            .filter { (_, expected) -> expected.isNotEmpty() && refs.any { r -> expected.any { it in r } } }
            .map { (q, expected) -> buildJsonObject { put("query", query(q)); put("expected", JsonArray(expected.map { JsonPrimitive(it) })) }.toString() }
        fun file(name: String, lines: List<String>) = dir.resolve(name).also { java.nio.file.Files.writeString(it, lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n") }
        return Files(
            pairs = file("pairs.jsonl", train),
            heldout = file("heldout.jsonl", held),
            corpus = file("corpus.jsonl", corpus.map { buildJsonObject { put("id", it.chunkId); put("text", it.passage); put("ref", ref(it.path, it.heading)) }.toString() }),
            eval = file("eval.jsonl", evals),
            train = train.size,
            held = held.size,
        )
    }

    private fun negatives(s: TrainingSample) = s.label.split(',').mapNotNull { it.trim().toLongOrNull() }
    private fun ref(path: String, heading: String?) = path + heading?.let { " > $it" }.orEmpty()
}
