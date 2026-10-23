package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.vesta.api.ActivateDto
import dev.vesta.api.ApiJson
import dev.vesta.api.TrainingRunDto
import dev.vesta.api.TrainingStateDto
import dev.vesta.app.ApiToken
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 모델 학습 (PROJECT.md 7.10, M14). 학습은 서버가 하고, 이 명령은 서버에 요청·확인만 한다 */
class TrainCommand : CliktCommand(name = "train") {
    override fun help(context: Context) = "결정 모델·임베딩 학습: 시작, 상태, 버전 전환·되돌리기"
    override fun run() = Unit

    companion object {
        fun create() = TrainCommand().subcommands(TrainDecisionCommand(), TrainEmbeddingCommand(), TrainGeneralCommand(), TrainStatusCommand(), TrainActivateCommand(), TrainCancelCommand(), TrainJudgeDataCommand())
    }
}

/** 서버 API (Bearer 토큰). 서버가 꺼져 있으면 알려 주고 끝낸다 */
internal class TrainingClient : AutoCloseable {
    private val base = ConfigLoader.load(VestaPaths.default().configFile).server.baseUrl
    private val token = ApiToken.read()
    // 임베딩 버전 전환은 모든 컬렉션 벡터를 다시 만들 때까지 기다린다 (CIO 기본 15초로는 끊긴다)
    private val http = HttpClient(CIO) { expectSuccess = false; engine { requestTimeout = 0 } }

    suspend fun get(path: String): String = check(runCatching { http.get(base + path) { token?.let { bearerAuth(it) } } })
    suspend fun post(path: String, body: String = "{}"): String = check(runCatching {
        http.post(base + path) { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body) }
    })

    private suspend fun check(result: Result<HttpResponse>): String {
        val response = result.getOrElse { throw TrainingError("서버에 연결하지 못했습니다 ($base). 학습은 서버가 합니다: scripts/install-vesta.sh") }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw TrainingError(runCatching { ApiJson.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content }.getOrDefault("서버 오류 ${response.status.value}"))
        }
        return text
    }

    override fun close() = http.close()
}

internal class TrainingError(message: String) : Exception(message)

private fun CliktCommand.withClient(block: suspend (TrainingClient) -> Unit) = runBlocking {
    TrainingClient().use { client ->
        try {
            block(client)
        } catch (e: TrainingError) {
            echo(e.message, err = true)
            throw ProgramResult(1)
        }
    }
}

class TrainDecisionCommand : CliktCommand(name = "decision") {
    override fun help(context: Context) = "결정 모델(라우팅·판정) 미세조정을 시작하고 끝날 때까지 진행 상황을 보여 준다 (1시간 넘게 걸릴 수 있다)"
    private val detach by option("--detach", help = "시작만 하고 바로 끝낸다").flag()

    override fun run() = withClient { client -> follow(client, "/api/training/decision", "결정 모델", detach) }
}

class TrainEmbeddingCommand : CliktCommand(name = "embedding") {
    override fun help(context: Context) = "임베딩 모델(bge-m3)을 검색 재료로 미세조정한다. 통과하면 모든 컬렉션 벡터를 새 모델로 바꾼다 (L-23)"
    private val detach by option("--detach", help = "시작만 하고 바로 끝낸다").flag()

    override fun run() = withClient { client -> follow(client, "/api/training/embedding", "임베딩", detach) }
}

class TrainGeneralCommand : CliktCommand(name = "general") {
    override fun help(context: Context) = "메인 모델을 👍 받은 일반 대화 답으로 QLoRA 학습해 후보를 만든다. 바로 쓰지 않고 주간 점검이 평가해 승인을 받는다 (L-24)"
    private val detach by option("--detach", help = "시작만 하고 바로 끝낸다").flag()

    override fun run() = withClient { client -> follow(client, "/api/training/general", "메인 모델", detach) }
}

private suspend fun CliktCommand.follow(client: TrainingClient, path: String, label: String, detach: Boolean) {
    val run = ApiJson.decodeFromString(TrainingRunDto.serializer(), client.post(path))
    echo("$label 학습 r${run.id} 시작. 웹 UI 상태 탭에서도 볼 수 있습니다.")
    if (detach) return
    var last = ""
    while (true) {
        delay(5_000)
        val state = ApiJson.decodeFromString(TrainingStateDto.serializer(), client.get("/api/training"))
        val current = state.runs.firstOrNull { it.id == run.id } ?: break
        val line = describe(current)
        if (line != last) { echo(line); last = line }
        if (current.status != "RUNNING") {
            echo(result(current, state))
            break
        }
    }
}

class TrainStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "지금 쓰는 결정 모델, 학습 기록, 버전"

    override fun run() = withClient { client ->
        val state = ApiJson.decodeFromString(TrainingStateDto.serializer(), client.get("/api/training"))
        echo("지금 결정 모델: ${state.decisionModel} (기본 ${state.baseModel})")
        state.embeddingModel?.let { echo("지금 임베딩 모델: $it (기본 ${state.embeddingBase})") }
        val pairs = state.samples["RETRIEVAL:ACCEPTED"] ?: 0
        echo("메인 모델 학습에 쓸 재료: 👍 받은 일반 대화 답 ${state.generalSamples}개 (최소 ${state.generalMinimum}개)")
        if (pairs > 0 || state.samples.containsKey("RETRIEVAL:PENDING")) echo("다음 임베딩 학습에 쓸 검색 재료: ${pairs}개 (검토 대기 ${state.samples["RETRIEVAL:PENDING"] ?: 0})")
        echo("다음 학습에 쓸 라우팅 정정: ${state.corrections}개")
        if (state.versions.isNotEmpty() || state.embeddingVersions.isNotEmpty()) {
            echo("버전:")
            (state.versions + state.embeddingVersions + state.generalVersions).forEach { echo("  ${if (it.active) "●" else "○"} ${it.name}  (r${it.runId}, ${it.createdAt.take(16)})") }
        }
        if (state.runs.isNotEmpty()) {
            echo("학습 기록:")
            state.runs.take(10).forEach { echo("  " + describe(it)) }
        }
    }
}

class TrainActivateCommand : CliktCommand(name = "activate") {
    override fun help(context: Context) = "결정 모델·임베딩 버전을 바꾼다. 기본 모델 이름(예: winnow:e4b, bge-m3:567m)이면 되돌린다"
    private val name by argument(help = "버전 이름 (vesta train status)")

    override fun run() = withClient { client ->
        // 이름으로 역할을 정한다: 임베딩 버전·기본 임베딩 모델이면 임베딩
        val state = ApiJson.decodeFromString(TrainingStateDto.serializer(), client.get("/api/training"))
        val embedding = name == state.embeddingBase || state.embeddingVersions.any { it.name == name }
        if (embedding) echo("모든 컬렉션 벡터를 $name 로 다시 만듭니다 (조각 수에 따라 몇 분 걸릴 수 있음)…")
        client.post("/api/training/activate", ApiJson.encodeToString(ActivateDto.serializer(), ActivateDto(name, if (embedding) "embedding" else "decision")))
        echo("${if (embedding) "임베딩" else "결정"} 모델을 $name 로 바꿨습니다 (서버에 바로 반영).")
    }
}

class TrainCancelCommand : CliktCommand(name = "cancel") {
    override fun help(context: Context) = "진행 중인 학습을 멈춘다"
    private val id by argument(help = "학습 번호 (r 뒤 숫자)")

    override fun run() = withClient { client ->
        client.post("/api/training/${id.removePrefix("r")}/cancel")
        echo("학습 r${id.removePrefix("r")}을 멈췄습니다.")
    }
}

/** 학습이 쓸 D6 코드 판정 재료를 미리 만들어 본다 (L-22). 서버가 아니라 이 Mac에서 직접 검색한다 */
class TrainJudgeDataCommand : CliktCommand(name = "judge-data") {
    override fun help(context: Context) = "D6 코드 판정 재료(학습용·시험용 JSONL)를 지금 저장소 검색으로 만들어 본다"
    private val out by option("--out", "-o", help = "저장할 폴더").path().default(java.nio.file.Path.of("."))

    override fun run() = runBlocking {
        dev.vesta.app.AppContainer().use { app ->
            val home = System.getProperty("user.home")
            fun path(value: String) = java.nio.file.Path.of(value.replaceFirst(Regex("^~"), home))
            val sets = mapOf(
                "code-judge-train.jsonl" to listOf(path(app.config.training.trainerDir).resolve("data/code-sufficiency-train-ko.tsv")),
                "code-judge-test.jsonl" to listOf("code-vesta.tsv", "code-absent-ko.tsv").map { path(app.config.training.evalDir).resolve(it) },
            )
            java.nio.file.Files.createDirectories(out)
            for ((name, files) in sets) {
                val rows = files.flatMap { dev.vesta.training.CodeSufficiencyData.rows(it) }
                val items = dev.vesta.training.CodeSufficiencyData.build(rows) { app.codeContext(it) }
                dev.vesta.training.CodeSufficiencyData.write(items, out.resolve(name))
                echo("$name: 질문 ${rows.size}개 → 재료 ${items.size}개 (있음 ${items.count { it.label }}, 없음 ${items.count { !it.label }}, 정답 파일을 못 찾아 뺌 ${rows.count { it.second.isNotEmpty() } - items.count { it.label }})")
            }
        }
    }
}

private fun describe(run: TrainingRunDto): String {
    val progress = if (run.total > 0) " ${run.step}/${run.total}" else ""
    val loss = run.loss?.let { " 손실 %.3f".format(it) }.orEmpty()
    return "r${run.id} ${statusName(run.status)} · ${run.stage ?: "-"}$progress$loss" + (run.error?.let { " · $it" } ?: "")
}

private fun result(run: TrainingRunDto, state: TrainingStateDto): String = when (run.status) {
    "PASSED" -> if (run.kind == "general") "후보 ${run.candidate} 을(를) 만들었습니다. 다음 주간 점검(vesta evolve run)이 평가하고, 통과하면 승인을 묻습니다" else "통과: ${run.candidate} 을(를) 쓰기 시작했습니다. 되돌리려면 vesta train activate ${if (run.kind == "embedding") state.embeddingBase else state.baseModel}"
    "REJECTED" -> "평가를 통과하지 못해 반영하지 않았습니다: ${run.error}"
    "CANCELLED" -> "취소됨"
    else -> "실패: ${run.error}"
}

private fun statusName(status: String) = when (status) {
    "RUNNING" -> "진행 중"
    "PASSED" -> "통과"
    "REJECTED" -> "반영 안 함"
    "CANCELLED" -> "취소"
    else -> "실패"
}
