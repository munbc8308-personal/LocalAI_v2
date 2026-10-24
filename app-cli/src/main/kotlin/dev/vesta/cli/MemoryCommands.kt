package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.vesta.api.ApiJson
import dev.vesta.api.MemoryDto
import dev.vesta.api.MemoryEditDto
import dev.vesta.app.ApiToken
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 장기 기억 (PROJECT.md MEM-05, M27). Mac 주인의 기억을 서버 API로 (임베딩은 서버가 만든다) */
class MemoryCommand : CliktCommand(name = "memory") {
    override fun help(context: Context) = "장기 기억: 보기·더하기·지우기 (대화에서는 \"기억해: …\", /forget, /memory)"
    override fun run() = Unit

    companion object {
        fun create() = MemoryCommand().subcommands(MemoryListCommand(), MemoryAddCommand(), MemoryRemoveCommand(), MemoryClearCommand(), MemoryScanCommand())
    }
}

private fun CliktCommand.memoryApi(method: HttpMethod, path: String, body: String? = null): String = runBlocking {
    val base = ConfigLoader.load(VestaPaths.default().configFile).server.baseUrl
    HttpClient(CIO) { expectSuccess = false }.use { http ->
        val response = runCatching {
            http.request(base + path) {
                this.method = method
                ApiToken.read()?.let { bearerAuth(it) }
                body?.let { contentType(ContentType.Application.Json); setBody(it) }
            }
        }.getOrElse { echo("서버에 연결하지 못했습니다 ($base). vesta status 로 확인하세요", err = true); throw ProgramResult(1) }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            echo(runCatching { ApiJson.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content }.getOrDefault("서버 오류 ${response.status.value}"), err = true)
            throw ProgramResult(1)
        }
        text
    }
}

private fun CliktCommand.memories(): List<MemoryDto> = ApiJson.decodeFromString(ListSerializer(MemoryDto.serializer()), memoryApi(HttpMethod.Get, "/api/memories"))

class MemoryListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "기억 목록 (최근 바뀐 순)"
    override fun run() {
        val all = memories()
        if (all.isEmpty()) { echo("아직 기억하는 것이 없습니다."); return }
        all.forEachIndexed { i, m ->
            echo("${(i + 1).toString().padStart(3)}. ${m.text}  [${m.kind}${if (m.source == "AUTO") "·자동" else ""}, 쓴 횟수 ${m.uses}]")
        }
    }
}

class MemoryAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "기억 더하기 (예: vesta memory add 나는 커피보다 차를 좋아해)"
    private val words by argument().multiple(required = true)
    override fun run() {
        val saved = ApiJson.decodeFromString(MemoryDto.serializer(), memoryApi(HttpMethod.Post, "/api/memories", ApiJson.encodeToString(MemoryEditDto.serializer(), MemoryEditDto(words.joinToString(" ")))))
        echo("기억했습니다: ${saved.text}")
    }
}

class MemoryRemoveCommand : CliktCommand(name = "rm") {
    override fun help(context: Context) = "기억 지우기 (list의 번호)"
    private val number by argument().multiple(required = true)
    override fun run() {
        val all = memories()
        number.mapNotNull { it.toIntOrNull() }.distinct().forEach { n ->
            val m = all.getOrNull(n - 1) ?: return@forEach echo("$n 번 기억은 없습니다", err = true)
            memoryApi(HttpMethod.Delete, "/api/memories/${m.id}")
            echo("지웠습니다: ${m.text}")
        }
    }
}

class MemoryClearCommand : CliktCommand(name = "clear") {
    override fun help(context: Context) = "기억을 모두 지운다. 되돌릴 수 없다"
    private val yes by option("--yes", help = "확인 없이").flag()
    override fun run() {
        if (!yes) {
            echo("기억 ${memories().size}개를 모두 지웁니다. 지우려면 '지우기'를 입력하세요: ", trailingNewline = false)
            if (readlnOrNull()?.trim() != "지우기") { echo("취소했습니다."); return }
        }
        echo("지웠습니다: ${ApiJson.parseToJsonElement(memoryApi(HttpMethod.Delete, "/api/memories")).jsonObject["removed"]}개")
    }
}

class MemoryScanCommand : CliktCommand(name = "scan") {
    override fun help(context: Context) = "자동 기억을 지금 한 번 돌린다 (보통은 쉬는 때 10분마다 저절로)"
    override fun run() {
        val saved = ApiJson.decodeFromString(ListSerializer(kotlinx.serialization.serializer<String>()), memoryApi(HttpMethod.Post, "/api/memories/scan"))
        if (saved.isEmpty()) echo("새로 기억한 것이 없습니다.") else saved.forEach { echo("기억함: $it") }
    }
}
