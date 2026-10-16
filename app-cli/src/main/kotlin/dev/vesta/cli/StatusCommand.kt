package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.VestaConfig
import dev.vesta.core.config.VestaPaths
import dev.vesta.inference.HttpClients
import dev.vesta.inference.OllamaAdmin
import dev.vesta.storage.VestaDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** 엔진·검색·결정 모델·Orchard·메모리 상태 확인 (PROJECT.md S-03). */
class StatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "엔진, 결정 모델, 검색, Orchard, 메모리 상태를 확인한다"

    override fun run() = runBlocking {
        val paths = VestaPaths.default()
        val config = ConfigLoader.load(paths.configFile)
        val http = HttpClients.local(requestTimeoutSeconds = 5)

        echo("설정    ${paths.configFile}${if (paths.configFile.exists()) "" else " (없음, 기본값 사용)"}")
        val db = runCatching { VestaDatabase.open(paths.database).also { it.migrate() } }
        echo("DB      " + db.fold({ "${paths.database} (schema v${it.schemaVersion()})" }, { "오류: ${it.message}" }))

        val checks = coroutineScope {
            listOf(
                async { checkOllama(config, http) },
                async { checkOllaya(config, http) },
                async { checkSearxng(config, http) },
                async { checkOrchard(config) },
            ).awaitAll()
        }
        checks.forEach { echo(it.format()) }
        echo(memoryLine(config, http))
        http.close()
    }

    private suspend fun checkOllama(config: VestaConfig, http: HttpClient) = check("Ollama", config.inference.ollamaUrl) {
        "v" + OllamaAdmin(config.inference.ollamaUrl, http).version()
    }

    private suspend fun checkOllaya(config: VestaConfig, http: HttpClient) = check("Ollaya", config.decision.baseUrl) {
        val response = http.get("${config.decision.baseUrl}/api/version")
        require(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        val version = Json.parseToJsonElement(response.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.content
        "v$version, 결정 모델 ${config.decision.model}"
    }

    private suspend fun checkSearxng(config: VestaConfig, http: HttpClient) = check("SearXNG", config.web.searxngUrl) {
        val response = http.get("${config.web.searxngUrl}/search?q=vesta&format=json")
        require(response.status.isSuccess()) { "HTTP ${response.status.value} (JSON 형식이 꺼져 있으면 403)" }
        val results = Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]
        "JSON 검색 응답 정상 (결과 ${(results as? kotlinx.serialization.json.JsonArray)?.size ?: 0}건)"
    }

    private fun checkOrchard(config: VestaConfig): Check {
        val server = config.mcp.servers["orchard"] ?: return Check("Orchard", "-", false, "설정에 없음")
        if (!server.enabled) return Check("Orchard", server.command, false, "비활성")
        return runCatching {
            val process = ProcessBuilder(server.command, "status").redirectErrorStream(true).start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            require(finished && process.exitValue() == 0) { output.lineSequence().firstOrNull() ?: "응답 없음" }
            val plan = Regex("Plan: (\\S+)").find(output)?.groupValues?.get(1) ?: "?"
            val enabled = Regex("\\[x]").findAll(output).count()
            Check("Orchard", "orchard-cli", true, "실행 중, 플랜 $plan, 기능 ${enabled}개 켜짐")
        }.getOrElse { Check("Orchard", "orchard-cli", false, it.message ?: it.toString()) }
    }

    private suspend fun memoryLine(config: VestaConfig, http: HttpClient): String {
        val total = MacSystem.totalMemoryBytes()
        val budget = total * config.memory.budgetPercent / 100
        val loaded = withTimeoutOrNull(3_000) {
            runCatching { OllamaAdmin(config.inference.ollamaUrl, http).loadedModels() }.getOrNull()
        }
        val used = loaded?.sumOf { it.sizeBytes } ?: 0
        val models = loaded?.joinToString { "${it.name} ${gb(it.sizeBytes)}" }?.ifEmpty { "없음" } ?: "확인 불가"
        return "메모리  전체 ${gb(total)}, 예산 ${config.memory.budgetPercent}% = ${gb(budget)}, 로드된 모델 ${gb(used)} ($models)"
    }

    private suspend fun check(name: String, target: String, block: suspend () -> String): Check =
        runCatching { Check(name, target, true, block()) }
            .getOrElse { Check(name, target, false, it.message ?: it::class.simpleName.orEmpty()) }

    private data class Check(val name: String, val target: String, val ok: Boolean, val detail: String) {
        fun format() = "${name.padEnd(8)}${if (ok) "OK  " else "FAIL"} $target — $detail"
    }
}

internal fun gb(bytes: Long) = "%.1fGB".format(bytes / 1024.0 / 1024.0 / 1024.0)

internal object MacSystem {
    fun totalMemoryBytes(): Long = runCatching {
        val process = ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.memsize").start()
        process.inputStream.bufferedReader().readText().trim().toLong()
    }.getOrElse { Runtime.getRuntime().maxMemory() }
}
