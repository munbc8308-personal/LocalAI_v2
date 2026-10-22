package dev.vesta.cli

import dev.vesta.app.AppContainer
import dev.vesta.core.ModelRole
import dev.vesta.core.evolution.CandidateRecord
import dev.vesta.core.evolution.EvolutionStatus
import dev.vesta.core.evolution.ProposalKind
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.training.TrainingStatus
import dev.vesta.evolution.Candidate
import dev.vesta.evolution.CandidatePicker
import dev.vesta.evolution.CatalogTag
import dev.vesta.evolution.Gate
import dev.vesta.evolution.OllamaLibraryCatalog
import dev.vesta.evolution.Scorecard
import dev.vesta.evolution.ThresholdTuner
import dev.vesta.evolution.TuneResult
import dev.vesta.evolution.Verdict
import dev.vesta.inference.HttpClients
import dev.vesta.inference.OllamaAdmin
import kotlinx.coroutines.flow.collect
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** 주간 점검 선택 사항 (`vesta evolve run` 옵션) */
internal data class WeeklyOptions(
    /** 비어 있지 않으면 모델 목록을 읽지 않고 이 모델들만 평가한다 */
    val candidates: List<String> = emptyList(),
    /** 없는 모델을 받지 않는다 (받아 둔 후보만 평가) */
    val pull: Boolean = true,
    val models: Boolean = true,
    val tune: Boolean = true,
    val retrain: Boolean = true,
    /** 평가만 하고 제안·반영·재학습·지우기를 하지 않는다 */
    val dryRun: Boolean = false,
)

/**
 * 주간 자가 개선 한 번 (PROJECT.md 7.13, M16). launchd가 일요일 새벽에 `vesta evolve run`으로 실행한다.
 * 1. 후보 찾기: 지켜보는 계열의 새 태그 + "새로 나온 모델" 중 도구 호출을 지원하는 계열 (E-02)
 * 2. 지금 모델과 후보를 같은 평가셋으로 잰다. 없는 후보는 받고, 떨어지면 지운다 (E-03·E-04)
 * 3. 통과한 후보 중 가장 좋은 것을 승인 요청으로 남긴다. 서버가 메신저로 보낸다 (E-05)
 * 4. 라우팅 정정이 쌓였으면 결정 모델을 다시 학습한다 (서버가 학습, 평가 게이트는 M14 그대로) (E-08)
 * 5. 라우터·D6 임계값을 다시 맞춰, 좋아지면 바로 반영한다 (E-06)
 */
internal class WeeklyCheck(private val options: WeeklyOptions, private val echo: (String) -> Unit) {

    suspend fun run(): String {
        val app = AppContainer()
        val repo = app.evolutionRepository
        repo.abandonRunning()
        val run = repo.startRun()
        val report = StringBuilder("## 주간 점검 ${LocalDate.now()}\n")
        fun note(line: String) { report.append(line).append('\n'); echo(line) }
        try {
            app.evolution.expireOld()
            val config = app.config.evolution
            val evalDir = Path.of(config.evalDir.replaceFirst(Regex("^~"), System.getProperty("user.home")))
                .takeIf { it.resolve("answers-ko.tsv").toFile().exists() } ?: Path.of("eval")
            val evaluator = ModelEvaluator(evalDir, echo)
            val admin = OllamaAdmin(app.config.inference.ollamaUrl, app.httpForEval)
            val current = app.config.models.general?.name ?: error("메인 모델이 설정에 없습니다 (models.general)")

            var base: Scorecard? = null
            if (options.models) {
                repo.stage(run.id, "후보 찾기")
                val candidates = findCandidates(app, current, admin, ::note)
                repo.stage(run.id, "지금 모델 평가")
                echo("지금 모델 평가: $current")
                base = evaluator.score(app)
                keep(app, run.id, current, evaluator.transcript)
                note("\n지금 모델 **$current**: ${line(base)}")

                val results = mutableListOf<Triple<Candidate, Scorecard?, Verdict?>>()
                val local = runCatching { admin.localModels().map { it.name }.toSet() }.getOrDefault(emptySet())
                for (picked in candidates) {
                    var candidate = picked
                    val pulled = candidate.name !in local
                    try {
                        if (pulled) {
                            // 시험 실행은 받지 않는다 (수십 GB를 받고 지우지 않게)
                            if (!options.pull || options.dryRun) { note("- ${candidate.name}: 받아 두지 않아 건너뜀 (${if (options.dryRun) "--dry-run" else "--no-pull"})"); continue }
                            repo.stage(run.id, "받기 ${candidate.name}")
                            echo("받기: ${candidate.name} (${gb(candidate.sizeBytes)})")
                            var shown = -1L
                            admin.pull(candidate.name) { done, total ->
                                val percent = done * 100 / total
                                if (percent / 10 != shown / 10) { shown = percent; repo.stage(run.id, "받기 ${candidate.name} $percent%"); echo(dim("  $percent%")) }
                            }
                        }
                        // 직접 지정한 후보는 받은 뒤 digest를 채운다 (모델 목록의 digest 앞 12자리와 같게 기록해 다음 점검에서 다시 평가하지 않게)
                        if (candidate.digest == "manual") runCatching { admin.localModels().firstOrNull { it.name == candidate.name } }.getOrNull()
                            ?.let { candidate = candidate.copy(digest = it.digest.take(12), sizeBytes = it.sizeBytes) }
                        repo.stage(run.id, "평가 ${candidate.name}")
                        echo("후보 평가: ${candidate.name}")
                        val card = AppContainer(generalOverride = candidate.name).use { evaluator.score(it) }
                        keep(app, run.id, candidate.name, evaluator.transcript)
                        val verdict = Gate.judge(base, card, config)
                        results += Triple(candidate, card, verdict)
                        note("- **${candidate.name}** (${candidate.reason}): ${line(card)} → " + if (verdict.passed) "통과 (${Gate.signed(verdict.delta)})" else "탈락: ${verdict.reasons.joinToString("; ")}")
                        if (!options.dryRun) repo.recordCandidate(CandidateRecord(candidate.name, candidate.digest, if (verdict.passed) "PASSED" else "REJECTED", card.composite, verdict.reasons.joinToString("; ").ifEmpty { null }, run.id, Instant.now()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        note("- ${candidate.name}: 평가 실패 (${e.message})")
                        results += Triple(candidate, null, null)
                        if (!options.dryRun) repo.recordCandidate(CandidateRecord(candidate.name, candidate.digest, "FAILED", null, e.message, run.id, Instant.now()))
                    } finally {
                        runCatching { admin.unload(candidate.name) }
                    }
                    // 떨어진 후보는 지운다 (이번에 받은 것만. 원래 있던 모델은 사용자가 받은 것)
                    val passed = results.lastOrNull()?.third?.passed == true
                    if (pulled && !passed && !options.dryRun) runCatching { admin.delete(candidate.name) }.onSuccess { echo(dim("  지움: ${candidate.name}")) }
                }

                val best = results.filter { it.third?.passed == true }.maxByOrNull { it.second!!.composite }
                if (best != null) {
                    val (candidate, card, verdict) = best
                    val changes = buildMap {
                        put("models.general.name", candidate.name)
                        if (app.config.models.code?.name == current) put("models.code.name", candidate.name)
                    }
                    if (!options.dryRun) {
                        val proposal = app.evolution.propose(run.id, ProposalKind.MODEL, "메인 모델 교체: $current → ${candidate.name}", comparison(base, card!!, verdict!!), changes)
                        note("\n→ 승인 요청 #${proposal.id}: 메인 모델을 ${candidate.name}(으)로 바꿀지 메신저로 묻습니다")
                    } else note("\n→ (시험 실행) 승인 요청을 만들지 않음: ${candidate.name}")
                } else if (candidates.isNotEmpty()) note("\n→ 지금 모델($current)을 그대로 씁니다")
            }

            // 재학습을 먼저 시작한다: 임계값을 반영하면 서버가 다시 시작하는데, 학습 중에는 학습이 끝날 때까지 기다린다
            if (options.retrain) {
                repo.stage(run.id, "결정 모델 재학습 확인")
                retrain(app, ::note)
            }
            if (options.tune && config.tune) {
                repo.stage(run.id, "임계값 조정")
                tune(app, evaluator, run.id, ::note)
            }
            // 평가하며 내려간 메인 모델을 다시 올려 둔다 (아침 첫 질문이 기다리지 않게)
            if (options.models) runCatching { warm(app) }
            if (options.models) note("\n문항별 답변: ${app.paths.home.resolve("evolution").resolve("run-${run.id}")}")
            repo.finishRun(run.id, EvolutionStatus.DONE, report.toString(), null)
            return report.toString()
        } catch (e: CancellationException) {
            repo.finishRun(run.id, EvolutionStatus.FAILED, report.toString(), "취소됨")
            throw e
        } catch (e: Exception) {
            repo.finishRun(run.id, EvolutionStatus.FAILED, report.toString(), e.message ?: e::class.simpleName)
            throw e
        } finally {
            app.close()
        }
    }

    private suspend fun findCandidates(app: AppContainer, current: String, admin: OllamaAdmin, note: (String) -> Unit): List<Candidate> {
        val config = app.config.evolution
        if (options.candidates.isNotEmpty()) {
            val local = runCatching { admin.localModels().associateBy { it.name } }.getOrDefault(emptyMap())
            return options.candidates.map { name ->
                Candidate(name, local[name]?.digest?.take(12) ?: "manual", local[name]?.sizeBytes ?: 0, name.substringBefore(':'), "직접 지정")
            }
        }
        val http = HttpClients.web(30)
        try {
            val catalog = OllamaLibraryCatalog(config.catalogUrl, http)
            val newest = runCatching { catalog.newest() }.onFailure { note("- 새 모델 목록을 읽지 못함: ${it.message}") }.getOrDefault(emptyList())
            val fresh = CandidatePicker.newFamilies(config, newest)
            if (fresh.isNotEmpty()) note("- 새로 나온 도구 호출 모델 계열: ${fresh.joinToString()}")
            val families = (listOf(current.substringBefore(':')) + config.families + fresh).distinct()
            val tags = families.associateWith { family ->
                runCatching { catalog.tags(family) }.onFailure { note("- $family 태그를 읽지 못함: ${it.message}") }.getOrDefault(emptyList())
            }
            if (tags.values.all { it.isEmpty() }) note("- 모델 목록을 읽지 못했습니다 (ollama.com 페이지 구조가 바뀌었을 수 있음)")
            val seen = mutableSetOf<CatalogTag>()
            for (tag in tags.values.flatten()) if (app.evolutionRepository.candidate(tag.name, tag.digest) != null) seen += tag
            val picked = CandidatePicker.pick(config, current, tags) { it in seen }
            note(if (picked.isEmpty()) "- 새 후보 없음 (평가한 적 없는 ${config.maxCandidateGigabytes}GB 이하 태그가 없음)" else "- 후보: " + picked.joinToString { "${it.name} (${gb(it.sizeBytes)})" })
            return picked
        } finally {
            http.close()
        }
    }

    private suspend fun tune(app: AppContainer, evaluator: ModelEvaluator, runId: Long, note: (String) -> Unit) {
        echo("임계값 조정…")
        val results = mutableListOf<TuneResult>()
        results += ThresholdTuner.agent(evaluator.routeSamples(app), app.config.decision.thresholds.agent)
        evaluator.codeSufficiency(app)?.let { (answerable, absent) ->
            results += ThresholdTuner.codeSufficient(answerable, absent, app.config.decision.thresholds.codeSufficient)
        } ?: note("- D6 임계값: 코드 저장소 '${app.config.evolution.codeCollection}'이나 평가셋이 없어 건너뜀")
        note("\n임계값")
        results.forEach { r ->
            note(
                "- ${r.key}: ${r.current}" + (if (r.changed) " → **${r.value}**" else " 그대로") +
                    " (맞춤 ${Gate.pct(r.trainBefore)}→${Gate.pct(r.trainAfter)}, 확인용 ${Gate.pct(r.holdoutBefore)}→${Gate.pct(r.holdoutAfter)}; ${r.note})",
            )
        }
        val changed = results.filter { it.changed }
        if (changed.isNotEmpty() && !options.dryRun) {
            val summary = changed.joinToString("\n") { "${it.key}: 평가셋 정확도 ${Gate.pct(it.trainBefore)} → ${Gate.pct(it.trainAfter)}, 확인용 ${Gate.pct(it.holdoutBefore)} → ${Gate.pct(it.holdoutAfter)} (${it.note})" }
            val applied = app.evolution.applyNow(runId, ProposalKind.TUNING, "임계값 조정", summary, changed.associate { it.key to it.value.toString() })
            note("→ 반영 #${applied.id} (되돌리기: vesta evolve rollback ${applied.id})")
        }
    }

    private suspend fun retrain(app: AppContainer, note: (String) -> Unit) {
        val config = app.config.evolution
        val last = app.trainingRuns.list(10).firstOrNull { it.status != TrainingStatus.FAILED && it.status != TrainingStatus.CANCELLED }
        // 학습에 동의한 사용자의 정정만 센다 (L-09)
        val fresh = app.users.trainingUsers().sumOf { user ->
            app.feedback.routingCorrections(user).count { last == null || it.createdAt.isAfter(last.startedAt) }
        }
        note("\n결정 모델: 지난 학습 뒤 라우팅 정정 ${fresh}건 (${config.retrainMinCorrections}건 이상이면 다시 학습)")
        if (fresh < config.retrainMinCorrections || options.dryRun) return
        runCatching { TrainingClient().use { it.post("/api/training/decision") } }
            .onSuccess { note("→ 결정 모델 재학습 시작 (서버, 평가 게이트를 통과하면 바로 바뀜)") }
            .onFailure { note("→ 재학습을 시작하지 못함: ${it.message}") }
    }

    /** 문항별 답변·판정을 `~/.vesta/evolution/run-<번호>/<모델>.md`에 남긴다 */
    private fun keep(app: AppContainer, runId: Long, model: String, transcript: String) = runCatching {
        val dir = app.paths.home.resolve("evolution").resolve("run-$runId")
        java.nio.file.Files.createDirectories(dir)
        java.nio.file.Files.writeString(dir.resolve(model.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".md"), transcript)
    }

    private suspend fun warm(app: AppContainer) {
        val spec = app.modelManager.acquire(ModelRole.GENERAL)
        app.inference.chat(ChatRequest(spec.name, listOf(ChatMessage(ChatRole.USER, "안녕")), maxTokens = 1, reasoningEffort = spec.reasoningEffort, keepAlive = spec.keepAlive)).collect()
    }

    companion object {
        fun gb(bytes: Long) = if (bytes <= 0) "?GB" else "%.0fGB".format(bytes / 1e9)

        fun line(s: Scorecard) =
            "종합 ${Gate.pct(s.composite)} · 답변 ${Gate.pct(s.answers)} · 라우팅 ${Gate.pct(s.routing)} · 근거 ${s.grounding?.let(Gate::pct) ?: "-"} · " +
                "한국어 ${Gate.pct(s.korean)} · 도구 ${Gate.pct(s.toolJson)} · 답변 시작 ${s.firstAnswerMs}ms · ${"%.0f".format(s.tokensPerSecond)} tok/s" +
                (s.memoryBytes?.let { " · ${gb(it)}" } ?: "")

        /** 승인 카드에 넣는 비교 (메신저마다 표가 다르게 보여 줄 단위로 쓴다) */
        fun comparison(base: Scorecard, candidate: Scorecard, verdict: Verdict) = buildString {
            fun row(name: String, a: String, b: String) = append("$name: $a → $b\n")
            row("종합", Gate.pct(base.composite), "${Gate.pct(candidate.composite)} (${Gate.signed(verdict.delta)})")
            row("답변 내용", Gate.pct(base.answers), Gate.pct(candidate.answers))
            row("라우팅", Gate.pct(base.routing), Gate.pct(candidate.routing))
            row("근거율", base.grounding?.let(Gate::pct) ?: "-", candidate.grounding?.let(Gate::pct) ?: "-")
            row("한국어", Gate.pct(base.korean), Gate.pct(candidate.korean))
            row("도구 형식", Gate.pct(base.toolJson), Gate.pct(candidate.toolJson))
            row("답변 시작", "${base.firstAnswerMs}ms", "${candidate.firstAnswerMs}ms")
            row("속도", "%.0f tok/s".format(base.tokensPerSecond), "%.0f tok/s".format(candidate.tokensPerSecond))
            row("메모리", base.memoryBytes?.let(::gb) ?: "?", candidate.memoryBytes?.let(::gb) ?: "?")
            append("승인하면 서버를 다시 시작해 새 모델을 씁니다. 이전 모델은 지우지 않아 되돌릴 수 있습니다.")
        }
    }
}
