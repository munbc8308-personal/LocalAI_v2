package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaPaths
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.inference.HttpClients
import dev.vesta.inference.OllamaAdmin
import dev.vesta.inference.OpenAiCompatibleClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * 모델 후보 벤치마크 (PROJECT.md M0, 4.10).
 * 첫 응답 시간(TTFT), 생성 속도(tok/s), 로드 시간, 메모리 사용량을 잰다. 품질은 출력을 저장해 사람이 비교한다.
 */
class BenchCommand : CliktCommand(name = "bench") {
    override fun help(context: Context) = "모델 속도·메모리 벤치마크. 결과는 ~/.vesta/bench/에 JSON으로 저장"

    private val models by option("--model", "-m", help = "측정할 모델 (여러 번 지정 가능)").multiple(required = true)
    private val runs by option("--runs", help = "프롬프트당 반복 횟수").int().default(2)
    private val maxTokens by option("--max-tokens", help = "응답 최대 토큰").int().default(512)
    private val suite by option("--suite", help = "프롬프트 묶음: general / code").default("general")
    private val efforts by option(
        "--reasoning-effort", "-r",
        help = "추론 모델의 생각 양 none / low / medium / high (여러 번 지정하면 각각 측정. none은 생각 끔)",
    ).choice("none", "low", "medium", "high").multiple()

    override fun run() = runBlocking {
        val paths = VestaPaths.default()
        val config = ConfigLoader.load(paths.configFile)
        val http = HttpClients.local(config.inference.requestTimeoutSeconds)
        val client = OpenAiCompatibleClient(config.inference.baseUrl, http)
        val admin = OllamaAdmin(config.inference.ollamaUrl, http)
        val prompts = BenchPrompts.suite(suite)

        val variants = models.flatMap { model -> efforts.ifEmpty { listOf(null) }.map { model to it } }
        val reports = variants.map { (model, effort) ->
            echo("== $model${effort?.let { " (reasoning_effort=$it)" }.orEmpty()} ==")
            // 첫 호출은 모델 로드가 포함되므로 따로 잰다
            val warmup = measure(client, model, BenchPrompts.WARMUP, 16, effort)
            echo("  로드 포함 첫 응답 ${warmup.ttftMs}ms")
            val results = prompts.flatMap { prompt ->
                (1..runs).map { run ->
                    measure(client, model, prompt, maxTokens, effort).also {
                        echo(
                            "  [${prompt.id} #$run] TTFT ${it.ttftMs}ms, 답변 시작 ${it.firstAnswerMs}ms, " +
                                "${"%.1f".format(it.decodeTokensPerSecond)} tok/s, 출력 ${it.completionTokens} tok (생각 조각 ${it.reasoningChunks})" +
                                (if (it.output.isBlank()) " ← 답변 없음" else ""),
                        )
                    }
                }
            }
            val memory = runCatching { admin.loadedModels().firstOrNull { it.name == model || it.name.startsWith("$model:") } }.getOrNull()
            ModelReport(
                model = model,
                reasoningEffort = effort,
                emptyAnswers = results.count { it.output.isBlank() },
                loadTtftMs = warmup.ttftMs,
                medianTtftMs = results.map { it.ttftMs }.median(),
                medianFirstAnswerMs = results.map { it.firstAnswerMs }.median(),
                medianDecodeTokensPerSecond = results.map { it.decodeTokensPerSecond }.median(),
                memoryBytes = memory?.sizeBytes,
                gpuMemoryBytes = memory?.gpuBytes,
                results = results,
            )
        }

        echo("")
        echo("모델".padEnd(40) + "TTFT(중앙)  답변시작(중앙)  tok/s(중앙)  로드+첫응답  빈 답변  메모리")
        reports.forEach {
            echo(
                (it.model + (it.reasoningEffort?.let { " ($it)" } ?: "")).padEnd(40) +
                    "${it.medianTtftMs}ms".padEnd(12) +
                    "${it.medianFirstAnswerMs}ms".padEnd(15) +
                    "%.1f".format(it.medianDecodeTokensPerSecond).padEnd(13) +
                    "${it.loadTtftMs}ms".padEnd(13) +
                    "${it.emptyAnswers}/${it.results.size}".padEnd(9) +
                    (it.memoryBytes?.let(::gb) ?: "?"),
            )
        }
        val file = paths.bench.resolve("bench-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json")
        paths.bench.createDirectories()
        file.writeText(BenchJson.encodeToString(BenchReport.serializer(), BenchReport(suite, runs, maxTokens, reports)))
        echo("\n저장: $file (응답 원문 포함 — 품질 비교용)")
        http.close()
    }

    private suspend fun measure(
        client: InferenceClient,
        model: String,
        prompt: BenchPrompt,
        maxTokens: Int,
        reasoningEffort: String?,
    ): RunResult {
        val messages = buildList {
            prompt.system?.let { add(ChatMessage(ChatRole.SYSTEM, it)) }
            add(ChatMessage(ChatRole.USER, prompt.user))
        }
        val stats = StreamStats.collect(client.chat(ChatRequest(model, messages, temperature = 0.2, maxTokens = maxTokens, reasoningEffort = reasoningEffort)))
        return RunResult(
            promptId = prompt.id,
            ttftMs = stats.ttftMs,
            firstAnswerMs = stats.firstAnswerMs,
            totalMs = stats.totalMs,
            promptTokens = stats.promptTokens,
            completionTokens = stats.completionTokens,
            decodeTokensPerSecond = stats.decodeTokensPerSecond,
            reasoningChunks = stats.reasoningChunks,
            output = stats.text,
            reasoning = stats.reasoning,
        )
    }
}

private val BenchJson = Json { prettyPrint = true }

@Serializable
data class BenchReport(val suite: String, val runs: Int, val maxTokens: Int, val models: List<ModelReport>)

@Serializable
data class ModelReport(
    val model: String,
    val reasoningEffort: String?,
    val emptyAnswers: Int,
    val loadTtftMs: Long,
    val medianTtftMs: Long,
    val medianFirstAnswerMs: Long,
    val medianDecodeTokensPerSecond: Double,
    val memoryBytes: Long?,
    val gpuMemoryBytes: Long?,
    val results: List<RunResult>,
)

@Serializable
data class RunResult(
    val promptId: String,
    val ttftMs: Long,
    val firstAnswerMs: Long,
    val totalMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int,
    val decodeTokensPerSecond: Double,
    val reasoningChunks: Int,
    val output: String,
    val reasoning: String,
)

internal fun List<Long>.median(): Long = sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

@JvmName("medianDouble")
internal fun List<Double>.median(): Double = sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
