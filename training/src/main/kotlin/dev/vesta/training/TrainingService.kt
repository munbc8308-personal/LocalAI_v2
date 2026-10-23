package dev.vesta.training

import dev.vesta.core.UserId
import dev.vesta.core.config.TrainingConfig
import dev.vesta.core.training.FeedbackRepository
import dev.vesta.core.training.ModelVersionRepository
import dev.vesta.core.training.TrainingRepository
import dev.vesta.core.training.TrainingRun
import dev.vesta.core.training.TrainingStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * 결정 모델 학습 작업 (PROJECT.md 7.10 L-01~L-07).
 * 데이터 → 변환(처음 한 번) → LoRA 학습 → Ollaya 모델로 내보내기 → 평가 게이트 → 통과하면 새 버전으로 전환.
 * Python 학습기(trainer)를 단계마다 자식 프로세스로 실행하고, 표준 출력의 JSON 줄로 진행률·손실을 받는다.
 * 한 번에 하나만 돈다. 취소하면 프로세스를 끝낸다.
 */
class TrainingService(
    private val config: TrainingConfig,
    private val runs: TrainingRepository,
    private val versions: ModelVersionRepository,
    private val feedback: FeedbackRepository,
    /** 학습 데이터로 쓸 정정을 남긴 사용자: OWNER와 동의한 사용자만 (L-09) */
    private val dataUsers: suspend () -> Set<UserId>,
    private val ollayaUrl: String,
    private val scope: CoroutineScope,
    /** 학습 전에 메모리 자리를 비운다 (ModelManager.reserve + 미디어 워커 내리기) */
    private val makeRoom: suspend (Long) -> Unit = {},
    /** 큐레이터가 모으고 검토를 거친 라우팅 재료 (L-25·L-26). 정정과 함께 학습에 넣는다 */
    private val samples: dev.vesta.core.training.TrainingSampleRepository? = null,
    /** D6 코드 판정 재료를 만들 저장소 검색 (L-22). 없으면 손으로 쓴 판정 재료만 쓴다 */
    private val codeContext: (suspend (String) -> dev.vesta.core.training.CodeContext?)? = null,
    /** 임베딩 학습 (L-23): 조각·지금 쓰는 임베딩 모델·통과하면 모든 컬렉션을 새 모델로 바꾸기. 없으면 임베딩 학습을 하지 않는다 */
    private val embedding: EmbeddingHooks? = null,
    /** 메인 모델 개인화 (L-24). 없으면 하지 않는다 */
    private val general: GeneralHooks? = null,
    private val home: String = System.getProperty("user.home"),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var job: Job? = null
    private var process: Process? = null

    /** 지금 학습하고 있는가 (큐레이터는 학습 중에 돌지 않는다, L-25) */
    val running: Boolean get() = job?.isActive == true

    private fun path(value: String): Path = Path.of(value.replaceFirst(Regex("^~"), home))
    private val trainer get() = path(config.trainerDir)
    private val work get() = path(config.workDir)

    /** 결정 모델 학습을 시작한다. 이미 돌고 있으면 그 실행을 돌려준다 */
    suspend fun startDecision(): TrainingRun = mutex.withLock {
        runs.list(1).firstOrNull()?.takeIf { it.status == TrainingStatus.RUNNING && job?.isActive == true }?.let { return it }
        check(trainer.resolve("vesta_train").exists()) { "학습기가 없습니다: $trainer (scripts/install-vesta.sh)" }
        val run = runs.start("decision", config.decisionBase)
        job = scope.launch(Dispatchers.IO) { execute(run) }
        run
    }

    /** 임베딩 모델 학습을 시작한다 (L-23). 이미 무엇이든 학습 중이면 그 실행을 돌려준다 */
    suspend fun startEmbedding(): TrainingRun = mutex.withLock {
        runs.list(1).firstOrNull()?.takeIf { it.status == TrainingStatus.RUNNING && job?.isActive == true }?.let { return it }
        val hooks = checkNotNull(embedding) { "임베딩 학습을 쓸 수 없습니다 (Ollama 임베딩 모델 설정 확인)" }
        check(trainer.resolve("vesta_train").exists()) { "학습기가 없습니다: $trainer (scripts/install-vesta.sh)" }
        val run = runs.start("embedding", config.embeddingBase)
        job = scope.launch(Dispatchers.IO) { executeEmbedding(run, hooks) }
        run
    }

    private suspend fun executeEmbedding(run: TrainingRun, hooks: EmbeddingHooks) {
        val dir = work.resolve("runs").resolve("r${run.id}")
        val candidate = "bge-m3-vesta:r${run.id}"
        try {
            Files.createDirectories(dir)
            runs.progress(run.id, "데이터", 0, 0, null)
            val current = hooks.current()
            val users = dataUsers()
            val data = EmbeddingData.write(dir, samples?.accepted(dev.vesta.core.training.SampleKind.RETRIEVAL, users).orEmpty(), hooks.passages, users, current,
                listOf("code-vesta.tsv", "retrieval-project-md.tsv").map { path(config.evalDir).resolve(it) }, hooks.query)
            check(data.train > 0) { "학습할 검색 재료가 없습니다 (상태 탭 학습 재료: 큐레이터가 쉬는 때 만든다)" }
            makeRoom(EMBEDDING_MEMORY)
            runs.progress(run.id, "학습", 0, 0, null)
            python(
                dir, "embed_train", "--gguf", ollamaGguf(config.embeddingBase).toString(), "--pairs", data.pairs.toString(),
                "--work", work.resolve("base").toString(), "--out", dir.resolve("model.gguf").toString(),
                "--epochs", config.embeddingEpochs.toString(), "--lr", config.embeddingLearningRate.toString(),
            ) { event ->
                if (event["event"]?.jsonPrimitive?.contentOrNull == "progress") runs.progress(run.id, "학습", event.int("step"), event.int("total"), event["loss"]?.jsonPrimitive?.doubleOrNull)
            }
            runs.progress(run.id, "내보내기", 0, 0, null)
            python(dir, "embed_register", "--gguf", dir.resolve("model.gguf").toString(), "--name", candidate, "--ollama", hooks.ollamaUrl)
            runs.progress(run.id, "평가", 0, 0, null)
            var gate: JsonObject? = null
            python(
                dir, "embed_verify", "--base", current, "--candidate", candidate, "--corpus", data.corpus.toString(), "--heldout", data.heldout.toString(),
                "--eval", data.eval.toString(), "--min-heldout", config.minHeldoutPairs.toString(), "--ollama", hooks.ollamaUrl,
            ) { event -> if (event["event"]?.jsonPrimitive?.contentOrNull == "gate") gate = event }
            val report = gate ?: error("평가 결과가 없습니다")
            if (report["passed"]?.jsonPrimitive?.booleanOrNull == true) {
                runs.progress(run.id, "전환", 0, 0, null)
                versions.add("embedding", candidate, config.embeddingBase, run.id, report.toString())
                // 모든 컬렉션의 벡터를 새 모델로 다시 만든 뒤 켠다 (컬렉션마다 한 번에 바뀐다)
                hooks.switch(candidate)
                versions.activate("embedding", candidate)
                runs.finish(run.id, TrainingStatus.PASSED, candidate, report.toString(), null)
                log.info { "임베딩 학습 통과 → $candidate 사용" }
            } else {
                val reasons = report["reasons"]?.jsonArray?.joinToString("; ") { it.jsonPrimitive.content }
                runs.finish(run.id, TrainingStatus.REJECTED, candidate, report.toString(), reasons)
                log.info { "임베딩 학습 반영 안 함: $reasons" }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (runs.get(run.id)?.status == TrainingStatus.RUNNING) runs.finish(run.id, TrainingStatus.CANCELLED, null, null, "취소됨") }
            throw e
        } catch (e: Exception) {
            if (runs.get(run.id)?.status != TrainingStatus.RUNNING) return
            log.error(e) { "임베딩 학습 실패 (r${run.id})" }
            runs.finish(run.id, TrainingStatus.FAILED, null, null, e.message ?: e::class.simpleName)
        }
    }

    /** 메인 모델 학습에 쓸 재료 수 (L-24) */
    suspend fun generalSamples(): Int {
        val hooks = general ?: return 0
        return GeneralData.rows(samples?.accepted(dev.vesta.core.training.SampleKind.ANSWER, dataUsers()).orEmpty(), hooks).size
    }

    /**
     * 메인 모델 QLoRA를 시작한다 (L-24). 결과는 바로 쓰지 않는다: Ollama에 `gemma4-vesta:26b-mxfp8-rN`으로 등록만 하고,
     * 주간 점검이 지금 모델과 같은 평가로 재서 통과하면 승인을 받은 뒤에만 바뀐다 (E-05)
     */
    suspend fun startGeneral(): TrainingRun = mutex.withLock {
        runs.list(1).firstOrNull()?.takeIf { it.status == TrainingStatus.RUNNING && job?.isActive == true }?.let { return it }
        val hooks = checkNotNull(general) { "메인 모델 학습을 쓸 수 없습니다" }
        check(trainer.resolve("vesta_train").exists()) { "학습기가 없습니다: $trainer (scripts/install-vesta.sh)" }
        val rows = GeneralData.rows(samples?.accepted(dev.vesta.core.training.SampleKind.ANSWER, dataUsers()).orEmpty(), hooks)
        check(rows.size >= config.minGeneralSamples) {
            "재료가 부족합니다: 👍 받은 일반 대화 답 ${rows.size}개 (최소 ${config.minGeneralSamples}개). 답에 👍·👎를 남기면 쌓입니다"
        }
        val base = hooks.current()
        val run = runs.start("general", base)
        job = scope.launch(Dispatchers.IO) { executeGeneral(run, rows, base) }
        run
    }

    private suspend fun executeGeneral(run: TrainingRun, rows: List<GeneralData.Row>, base: String) {
        val dir = work.resolve("runs").resolve("r${run.id}")
        // 원본 태그를 이름에 남긴다: Ollama가 gemma4 렌더러(빈 생각 칸을 채우는 large / 아닌 small)를 이름으로 고른다 (13.30)
        val candidate = base.split(':').let { "${it[0]}-vesta:${it.getOrElse(1) { "latest" }}-r${run.id}" }
        try {
            Files.createDirectories(dir)
            runs.progress(run.id, "데이터", 0, 0, null)
            val data = GeneralData.write(rows, dir.resolve("sft.jsonl"))
            makeRoom(config.generalMemoryGigabytes * 1024L * 1024 * 1024)
            runs.progress(run.id, "학습", 0, 0, null)
            var registered: JsonObject? = null
            python(
                dir, "general_train", "--base", base, "--models", path(config.ollamaModels).toString(), "--data", data.toString(),
                "--work", dir.resolve("work").toString(), "--name", candidate,
                "--epochs", config.generalEpochs.toString(), "--lr", config.generalLearningRate.toString(),
            ) { event ->
                when (event["event"]?.jsonPrimitive?.contentOrNull) {
                    "progress" -> runs.progress(run.id, "학습", event.int("step"), event.int("total"), event["loss"]?.jsonPrimitive?.doubleOrNull)
                    "registered" -> registered = event
                }
            }
            val report = registered ?: error("등록 결과가 없습니다")
            versions.add("general", candidate, base, run.id, report.toString())
            runs.finish(run.id, TrainingStatus.PASSED, candidate, report.toString(), null)
            log.info { "메인 모델 학습 끝 → 후보 $candidate (주간 점검에서 평가·승인)" }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (runs.get(run.id)?.status == TrainingStatus.RUNNING) runs.finish(run.id, TrainingStatus.CANCELLED, null, null, "취소됨") }
            throw e
        } catch (e: Exception) {
            if (runs.get(run.id)?.status != TrainingStatus.RUNNING) return
            log.error(e) { "메인 모델 학습 실패 (r${run.id})" }
            runs.finish(run.id, TrainingStatus.FAILED, null, null, e.message ?: e::class.simpleName)
        }
    }

    /** Ollama 매니페스트에서 모델의 GGUF 파일을 찾는다 */
    private fun ollamaGguf(model: String): Path {
        val (name, tag) = model.split(':').let { it[0] to (it.getOrNull(1) ?: "latest") }
        val models = path(config.ollamaModels)
        val manifest = json.parseToJsonElement(models.resolve("manifests/registry.ollama.ai/library/$name/$tag").readText()).jsonObject
        val digest = manifest["layers"]!!.jsonArray.map { it.jsonObject }
            .first { it["mediaType"]!!.jsonPrimitive.content == "application/vnd.ollama.image.model" }["digest"]!!.jsonPrimitive.content
        return models.resolve("blobs").resolve(digest.replace(':', '-')).also { check(it.exists()) { "Ollama 모델 파일이 없습니다: $model" } }
    }

    suspend fun cancel(id: Long): Boolean = mutex.withLock {
        val running = runs.get(id)?.takeIf { it.status == TrainingStatus.RUNNING } ?: return false
        process?.destroy()
        job?.cancel()
        runs.finish(running.id, TrainingStatus.CANCELLED, null, null, "사용자가 취소함")
        true
    }

    private suspend fun execute(run: TrainingRun) {
        val dir = work.resolve("runs").resolve("r${run.id}")
        val candidate = "winnow-vesta:r${run.id}"
        try {
            Files.createDirectories(dir)
            runs.progress(run.id, "데이터", 0, 0, null)
            val corrections = writeCorrections(dir.resolve("corrections.tsv"))
            // 판정 재료 (L-22): 손으로 쓴 D6·D7·D10 + 지금 저장소로 만든 D6 코드. 시험용 코드 질문은 주간 점검 평가셋(D6 임계값과 같은 것)
            val codeTrain = codeJudge(listOf(trainer.resolve("data").resolve("code-sufficiency-train-ko.tsv")), dir.resolve("code-judge-train.jsonl"))
            val codeTest = codeJudge(listOf("code-vesta.tsv", "code-absent-ko.tsv").map { path(config.evalDir).resolve(it) }, dir.resolve("code-judge-test.jsonl"))
            val base = baseGguf(config.decisionBase)
            val hf = work.resolve("base").resolve(config.decisionBase.replace(':', '-') + "-hf")
            if (!hf.resolve("model.safetensors").exists() && !hf.resolve("model.safetensors.index.json").exists()) {
                runs.progress(run.id, "기본 모델 변환", 0, 0, null)
                python(dir, "convert", "--gguf", base.toString(), "--config", data("gemma-4-E4B-it-config.json"), "--out", hf.toString())
            }
            makeRoom(config.memoryGigabytes * 1024L * 1024 * 1024)
            runs.progress(run.id, "학습", 0, 0, null)
            val trainFiles = listOf(data("routing-train-ko.tsv")) + listOfNotNull(corrections?.toString())
            val judgeTrain = listOf(data("judge-train-ko.jsonl")) + listOfNotNull(codeTrain?.toString())
            python(
                dir, "train", "--base", hf.toString(), "--train", *trainFiles.toTypedArray(), "--judge", *judgeTrain.toTypedArray(),
                "--replay", data("judge-replay.jsonl"), "--out", dir.resolve("adapter").toString(),
                "--epochs", config.epochs.toString(), "--rank", config.rank.toString(), "--lr", config.learningRate.toString(),
                "--ollaya", ollayaUrl,
            ) { event ->
                if (event["event"]?.jsonPrimitive?.contentOrNull == "progress") {
                    runs.progress(run.id, "학습", event.int("step"), event.int("total"), event["loss"]?.jsonPrimitive?.doubleOrNull)
                }
            }
            runs.progress(run.id, "내보내기", 0, 0, null)
            python(dir, "export", "--base", base.toString(), "--adapter", dir.resolve("adapter").toString(), "--out", dir.resolve("model.gguf").toString(), "--name", candidate)
            runs.progress(run.id, "평가", 0, 0, null)
            var gate: JsonObject? = null
            python(
                dir, "verify", "--base", config.decisionBase, "--candidate", candidate,
                "--test", data("routing-test-ko.tsv"), data("routing-eval-m4-ko.tsv"),
                "--judge-test", data("judge-test-ko.jsonl"), *listOfNotNull(codeTest?.toString()).toTypedArray(),
                "--replay", data("judge-replay.jsonl"), "--min-replay", config.minReplayAgreement.toString(), "--ollaya", ollayaUrl,
            ) { event -> if (event["event"]?.jsonPrimitive?.contentOrNull == "gate") gate = event }
            val report = gate ?: error("평가 결과가 없습니다")
            val passed = report["passed"]?.jsonPrimitive?.booleanOrNull == true
            if (passed) {
                versions.add("decision", candidate, config.decisionBase, run.id, report.toString())
                versions.activate("decision", candidate)
                runs.finish(run.id, TrainingStatus.PASSED, candidate, report.toString(), null)
                log.info { "결정 모델 학습 통과 → $candidate 사용" }
            } else {
                val reasons = report["reasons"]?.jsonArray?.joinToString("; ") { it.jsonPrimitive.content }
                runs.finish(run.id, TrainingStatus.REJECTED, candidate, report.toString(), reasons)
                log.info { "결정 모델 학습 반영 안 함: $reasons" }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (runs.get(run.id)?.status == TrainingStatus.RUNNING) runs.finish(run.id, TrainingStatus.CANCELLED, null, null, "취소됨") }
            throw e
        } catch (e: Exception) {
            // 취소로 프로세스를 끝낸 경우는 이미 CANCELLED로 적었다
            if (runs.get(run.id)?.status != TrainingStatus.RUNNING) return
            log.error(e) { "학습 실패 (r${run.id})" }
            runs.finish(run.id, TrainingStatus.FAILED, null, null, e.message ?: e::class.simpleName)
        }
    }

    private fun data(name: String) = trainer.resolve("data").resolve(name).toString()

    /** 사용자가 남긴 라우팅 정정 (L-01·L-02). 담당자만 알므로 웹·로컬 칸은 "-". 없으면 null */
    private suspend fun writeCorrections(target: Path): Path? {
        val users = dataUsers()
        val curated = samples?.accepted(dev.vesta.core.training.SampleKind.ROUTING, users).orEmpty()
            .mapNotNull { s -> runCatching { dev.vesta.core.AgentType.valueOf(s.label) }.getOrNull()?.let { dev.vesta.core.training.RoutingCorrection(s.question, it, s.createdAt) } }
        // 직접 고친 정정이 같은 질문의 큐레이터 재료보다 앞선다 (distinctBy는 앞의 것을 남긴다)
        val rows = (users.flatMap { feedback.routingCorrections(it) }.sortedBy { it.createdAt } + curated)
            .map { PrivacyFilter.cell(it.question) to it.agent.name }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
        if (rows.isEmpty()) return null
        Files.writeString(target, "# 사용자 라우팅 정정 (C-09). 열: 질문<TAB>담당자<TAB>웹(-: 모름)<TAB>로컬(-)\n" + rows.joinToString("\n") { "${it.first}\t${it.second}\t-\t-" } + "\n")
        return target
    }

    /** 코드 질문 TSV → 실제 저장소 검색으로 정답 붙은 D6 재료 (L-22). 저장소가 없거나 재료가 없으면 null */
    private suspend fun codeJudge(files: List<Path>, target: Path): Path? {
        val search = codeContext ?: return null
        val items = CodeSufficiencyData.build(files.flatMap { CodeSufficiencyData.rows(it) }, search)
        log.info { "D6 코드 판정 재료 ${items.size}개 (${target.fileName})" }
        return CodeSufficiencyData.write(items, target)
    }

    /** Ollaya 매니페스트에서 모델의 GGUF 파일을 찾는다 */
    private fun baseGguf(model: String): Path {
        val (name, tag) = model.split(':').let { it[0] to (it.getOrNull(1) ?: "latest") }
        val models = path(config.ollayaModels)
        val manifest = json.parseToJsonElement(models.resolve("manifests/ollaya.dev/library/$name/$tag").readText()).jsonObject
        val digest = manifest["layers"]!!.jsonArray.map { it.jsonObject }
            .first { it["mediaType"]!!.jsonPrimitive.content == "application/vnd.ollaya.weights.gguf" }["digest"]!!.jsonPrimitive.content
        return models.resolve("blobs").resolve(digest.replace(':', '-')).also { check(it.exists()) { "Ollaya 모델 파일이 없습니다: $model" } }
    }

    /** `uv run python -m vesta_train.<module> …`. 표준 출력의 JSON 줄을 [onEvent]로, 전체 출력은 실행 폴더의 로그로 */
    private suspend fun python(dir: Path, module: String, vararg args: String, onEvent: suspend (JsonObject) -> Unit = {}) {
        val command = listOf(config.uv, "run", "--project", trainer.toString(), "--frozen", "python", "-m", "vesta_train.$module") + args
        val logFile = dir.resolve("$module.log").toFile()
        val started = ProcessBuilder(command).directory(trainer.toFile())
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .apply { environment()["HF_HUB_DISABLE_XET"] = "1"; environment()["PYTHONUNBUFFERED"] = "1" }
            .start()
        process = started
        try {
            started.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    logFile.appendText(line + "\n")
                    if (line.startsWith("{")) runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()?.let { onEvent(it) }
                }
            }
            val code = runInterruptible { started.waitFor() }
            check(code == 0) { "학습기($module) 실패 (종료 코드 $code): ${tail(logFile)}" }
        } finally {
            if (started.isAlive) started.destroy()
            process = null
        }
    }

    private fun tail(file: File) = runCatching { file.readLines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ").take(500) }.getOrDefault("")

    private companion object {
        /** 임베딩 학습 메모리 (bge-m3 전체 미세조정, 묶음 16) */
        const val EMBEDDING_MEMORY = 16L * 1024 * 1024 * 1024
    }

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
}
