package dev.vesta.training

import dev.vesta.core.AgentType
import dev.vesta.core.UserId
import dev.vesta.core.config.TrainingConfig
import dev.vesta.core.training.Feedback
import dev.vesta.core.training.FeedbackRepository
import dev.vesta.core.training.ModelVersion
import dev.vesta.core.training.ModelVersionRepository
import dev.vesta.core.training.RoutingCorrection
import dev.vesta.core.training.TrainingRepository
import dev.vesta.core.training.TrainingRun
import dev.vesta.core.training.TrainingStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

private class MemoryRuns : TrainingRepository {
    val items = mutableMapOf<Long, TrainingRun>()
    override suspend fun start(kind: String, baseModel: String) =
        TrainingRun(items.size + 1L, kind, TrainingStatus.RUNNING, baseModel, 0, 0, null, null, null, null, null, Instant.EPOCH, null).also { items[it.id] = it }
    override suspend fun progress(id: Long, stage: String, step: Int, total: Int, loss: Double?) {
        items[id] = items.getValue(id).copy(stage = stage, step = step, total = total, loss = loss ?: items.getValue(id).loss)
    }
    override suspend fun finish(id: Long, status: TrainingStatus, candidate: String?, report: String?, error: String?) {
        items[id] = items.getValue(id).copy(status = status, candidate = candidate, report = report, error = error, finishedAt = Instant.EPOCH)
    }
    override suspend fun get(id: Long) = items[id]
    override suspend fun list(limit: Int) = items.values.sortedByDescending { it.id }.take(limit)
    override suspend fun abandonRunning() = 0
}

private class MemoryVersions : ModelVersionRepository {
    val items = mutableListOf<ModelVersion>()
    override suspend fun add(role: String, name: String, baseModel: String, runId: Long?, metrics: String?) =
        ModelVersion(items.size + 1L, role, name, baseModel, runId, false, metrics, Instant.EPOCH).also { items += it }
    override suspend fun activate(role: String, name: String) { items.replaceAll { it.copy(active = it.name == name) } }
    override suspend fun active(role: String) = items.firstOrNull { it.active }
    override suspend fun list(role: String) = items.toList()
}

private class Corrections(val list: List<RoutingCorrection>) : FeedbackRepository {
    override suspend fun save(userId: UserId, feedback: Feedback) = true
    override suspend fun find(userId: UserId, messageId: Long) = null
    override suspend fun routingCorrections(userId: UserId?) = list
}

/** 가짜 uv: 받은 모듈 이름에 따라 학습기 JSON 줄을 낸다. 인자는 args.log에 남긴다 */
private fun fakeUv(dir: Path, passed: Boolean, slowTrain: Boolean = false): Path {
    val script = dir.resolve("uv")
    script.writeText(
        """
        #!/bin/sh
        echo "${'$'}@" >> "${dir}/args.log"
        case "${'$'}*" in
          *vesta_train.train*)
            echo '{"event": "progress", "step": 1, "total": 2, "loss": 0.5}'
            ${if (slowTrain) "sleep 30" else ""}
            echo '{"event": "progress", "step": 2, "total": 2, "loss": 0.25}' ;;
          *vesta_train.verify*)
            echo '{"event": "gate", "passed": $passed, "reasons": ["정답 수가 늘지 않음"], "base": {"right": {"agent": 1}}, "candidate": {"right": {"agent": 1}}}' ;;
        esac
        exit 0
        """.trimIndent(),
    )
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
    return script
}

private fun setup(passed: Boolean, slowTrain: Boolean = false, corrections: List<RoutingCorrection> = emptyList()): Triple<TrainingService, MemoryRuns, Pair<MemoryVersions, Path>> {
    val home = Files.createTempDirectory("vesta-train")
    val trainer = home.resolve("trainer").also { it.resolve("vesta_train").createDirectories(); it.resolve("data").createDirectories() }
    // Ollaya 저장소: 매니페스트 → GGUF 파일
    val models = home.resolve("ollaya")
    models.resolve("manifests/ollaya.dev/library/winnow").createDirectories()
    models.resolve("blobs").createDirectories()
    models.resolve("blobs/sha256-abc").writeText("gguf")
    models.resolve("manifests/ollaya.dev/library/winnow/e4b").writeText("""{"layers": [{"mediaType": "application/vnd.ollaya.weights.gguf", "digest": "sha256:abc"}]}""")
    // 변환은 이미 한 것으로
    home.resolve("work/base/winnow-e4b-hf").createDirectories().resolve("model.safetensors").writeText("x")
    val config = TrainingConfig(trainerDir = trainer.toString(), workDir = home.resolve("work").toString(), uv = fakeUv(home, passed, slowTrain).toString(), ollayaModels = models.toString())
    val runs = MemoryRuns()
    val versions = MemoryVersions()
    val service = TrainingService(config, runs, versions, Corrections(corrections), UserId(1), "http://ollaya", CoroutineScope(SupervisorJob() + Dispatchers.IO))
    return Triple(service, runs, versions to home)
}

private suspend fun MemoryRuns.waitDone(id: Long): TrainingRun = withTimeout(20_000) {
    while (items.getValue(id).status == TrainingStatus.RUNNING) delay(50)
    items.getValue(id)
}

class TrainingServiceTest : FunSpec({
    test("통과하면 새 버전을 등록해 쓰고, 정정은 개인정보를 지워 학습 데이터로 넘긴다") {
        val corrections = listOf(RoutingCorrection("010-1234-5678로 문자 보내 줘", AgentType.PERSONAL, Instant.EPOCH))
        val (service, runs, rest) = setup(passed = true, corrections = corrections)
        val (versions, home) = rest
        val run = runs.waitDone(service.startDecision().id)
        run.status shouldBe TrainingStatus.PASSED
        run.candidate shouldBe "winnow-vesta:r1"
        run.step shouldBe 0 // 마지막 단계(평가) 진행은 0으로 바뀐다
        versions.active("decision")?.name shouldBe "winnow-vesta:r1"
        val args = home.resolve("args.log").readText()
        args shouldContain "vesta_train.train"
        args shouldContain "corrections.tsv"
        args shouldContain "--candidate winnow-vesta:r1"
        args shouldContain home.resolve("ollaya/blobs/sha256-abc").toString()
        val data = home.resolve("work/runs/r1/corrections.tsv").readText()
        data shouldContain "[제거됨]로 문자 보내 줘\tPERSONAL\t-\t-"
        data shouldNotContain "1234"
    }

    test("평가를 통과하지 못하면 반영하지 않고 이유를 남긴다") {
        val (service, runs, rest) = setup(passed = false)
        val run = runs.waitDone(service.startDecision().id)
        run.status shouldBe TrainingStatus.REJECTED
        run.error shouldBe "정답 수가 늘지 않음"
        rest.first.active("decision") shouldBe null
        rest.second.resolve("args.log").readText() shouldNotContain "corrections.tsv"
    }

    test("학습 중에 취소하면 프로세스를 끝내고 취소로 남는다. 같은 때 두 번 시작하지 않는다") {
        val (service, runs, _) = setup(passed = true, slowTrain = true)
        val run = service.startDecision()
        withTimeout(10_000) { while (runs.items.getValue(run.id).loss == null) delay(50) }
        service.startDecision().id shouldBe run.id
        service.cancel(run.id) shouldBe true
        delay(500)
        runs.items.getValue(run.id).status shouldBe TrainingStatus.CANCELLED
    }

    test("개인정보 지우기") {
        PrivacyFilter.clean("메일 kim.minsu@example.com, 번호 010 9876 5432, 카드 1234-5678-9012-3456, 비밀번호: hunter2") shouldBe
            "메일 [제거됨], 번호 [제거됨], 카드 [제거됨], [제거됨]"
        // 실제 토큰처럼 보이는 문자열을 저장소에 두지 않으려고 조립한다
        PrivacyFilter.clean("토큰 " + "sk" + "-" + "abcdefghijklmnop1234" + " 은 지운다") shouldBe "토큰 [제거됨] 은 지운다"
        PrivacyFilter.clean("내일 3시 회의 일정 알려 줘") shouldBe "내일 3시 회의 일정 알려 줘"
    }
})
