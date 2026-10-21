package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
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
    override fun help(context: Context) = "결정 모델 학습: 시작, 상태, 버전 전환·되돌리기"
    override fun run() = Unit

    companion object {
        fun create() = TrainCommand().subcommands(TrainDecisionCommand(), TrainStatusCommand(), TrainActivateCommand(), TrainCancelCommand())
    }
}

/** 서버 API (Bearer 토큰). 서버가 꺼져 있으면 알려 주고 끝낸다 */
private class TrainingClient : AutoCloseable {
    private val base = ConfigLoader.load(VestaPaths.default().configFile).server.baseUrl
    private val token = ApiToken.read()
    private val http = HttpClient(CIO) { expectSuccess = false }

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

private class TrainingError(message: String) : Exception(message)

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
    override fun help(context: Context) = "결정 모델(라우팅) 미세조정을 시작하고 끝날 때까지 진행 상황을 보여 준다 (1시간 넘게 걸릴 수 있다)"
    private val detach by option("--detach", help = "시작만 하고 바로 끝낸다").flag()

    override fun run() = withClient { client ->
        val run = ApiJson.decodeFromString(TrainingRunDto.serializer(), client.post("/api/training/decision"))
        echo("결정 모델 학습 r${run.id} 시작. 웹 UI 상태 탭에서도 볼 수 있습니다.")
        if (detach) return@withClient
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
}

class TrainStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "지금 쓰는 결정 모델, 학습 기록, 버전"

    override fun run() = withClient { client ->
        val state = ApiJson.decodeFromString(TrainingStateDto.serializer(), client.get("/api/training"))
        echo("지금 결정 모델: ${state.decisionModel} (기본 ${state.baseModel})")
        echo("다음 학습에 쓸 라우팅 정정: ${state.corrections}개")
        if (state.versions.isNotEmpty()) {
            echo("버전:")
            state.versions.forEach { echo("  ${if (it.active) "●" else "○"} ${it.name}  (r${it.runId}, ${it.createdAt.take(16)})") }
        }
        if (state.runs.isNotEmpty()) {
            echo("학습 기록:")
            state.runs.take(10).forEach { echo("  " + describe(it)) }
        }
    }
}

class TrainActivateCommand : CliktCommand(name = "activate") {
    override fun help(context: Context) = "결정 모델 버전을 바꾼다. 기본 모델 이름(예: winnow:e4b)이면 되돌린다"
    private val name by argument(help = "버전 이름 (vesta train status)")

    override fun run() = withClient { client ->
        client.post("/api/training/activate", ApiJson.encodeToString(ActivateDto.serializer(), ActivateDto(name)))
        echo("결정 모델을 $name 로 바꿨습니다 (서버에 바로 반영).")
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

private fun describe(run: TrainingRunDto): String {
    val progress = if (run.total > 0) " ${run.step}/${run.total}" else ""
    val loss = run.loss?.let { " 손실 %.3f".format(it) }.orEmpty()
    return "r${run.id} ${statusName(run.status)} · ${run.stage ?: "-"}$progress$loss" + (run.error?.let { " · $it" } ?: "")
}

private fun result(run: TrainingRunDto, state: TrainingStateDto): String = when (run.status) {
    "PASSED" -> "통과: ${run.candidate} 을(를) 쓰기 시작했습니다. 되돌리려면 vesta train activate ${state.baseModel}"
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
