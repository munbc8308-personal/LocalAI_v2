package dev.vesta.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnlyHint: Boolean?,
    val destructiveHint: Boolean?,
    val openWorldHint: Boolean?,
)

data class McpCallResult(val text: String, val isError: Boolean)

class McpException(message: String) : RuntimeException(message)

/**
 * MCP stdio 클라이언트 (PROJECT.md 7.7a, A-01). 서버를 자식 프로세스로 띄우고 줄 단위 JSON-RPC로 대화한다.
 * 필요한 메서드만 구현한다: initialize, tools/list, tools/call, resources/read.
 * 프로세스가 죽으면 다음 요청 때 다시 띄운다.
 */
class McpStdioClient(
    val name: String,
    private val command: List<String>,
    private val requestTimeout: Duration = 60.seconds,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val startLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: Job? = null

    suspend fun listTools(): List<McpToolInfo> {
        val tools = request("tools/list", buildJsonObject { })["tools"]?.jsonArray.orEmpty()
        return tools.map { element ->
            val tool = element.jsonObject
            val annotations = tool["annotations"]?.jsonObject
            McpToolInfo(
                name = tool.getValue("name").jsonPrimitive.content,
                description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = tool["inputSchema"]?.jsonObject ?: buildJsonObject { put("type", "object") },
                readOnlyHint = annotations?.get("readOnlyHint")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                destructiveHint = annotations?.get("destructiveHint")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                openWorldHint = annotations?.get("openWorldHint")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
            )
        }
    }

    suspend fun callTool(tool: String, arguments: JsonObject): McpCallResult {
        val result = request("tools/call", buildJsonObject {
            put("name", tool)
            put("arguments", arguments)
        })
        val text = result["content"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.takeIf { c -> c["type"]?.jsonPrimitive?.content == "text" }?.get("text")?.jsonPrimitive?.content }
            .joinToString("\n")
        return McpCallResult(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    suspend fun readResource(uri: String): String =
        request("resources/read", buildJsonObject { put("uri", uri) })["contents"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        ensureStarted()
        return send(method, params)
    }

    private suspend fun send(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        withContext(Dispatchers.IO) {
            val out = writer ?: throw McpException("$name: 서버가 실행 중이 아님")
            synchronized(out) {
                out.write(message.toString())
                out.write("\n")
                out.flush()
            }
        }
        try {
            val response = withTimeout(requestTimeout) { deferred.await() }
            response["error"]?.jsonObject?.let { error ->
                throw McpException("$name $method: ${error["message"]?.jsonPrimitive?.contentOrNull ?: error}")
            }
            return response["result"]?.jsonObject ?: buildJsonObject { }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun ensureStarted() = startLock.withLock {
        if (process?.isAlive == true) return@withLock
        log.info { "MCP 서버 시작: $name ${command.joinToString(" ")}" }
        val started = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = started
        writer = started.outputStream.bufferedWriter()
        reader = scope.launch {
            started.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    val id = message["id"]?.jsonPrimitive?.intOrNull ?: return@forEach // 서버 알림은 무시
                    pending[id]?.complete(message)
                }
            }
            // 프로세스 종료: 기다리던 요청은 실패로
            pending.values.forEach { it.completeExceptionally(McpException("$name: 서버가 종료됨")) }
        }
        send("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { }
            putJsonObject("clientInfo") {
                put("name", "vesta")
                put("version", "0.1")
            }
        })
        withContext(Dispatchers.IO) {
            writer!!.apply {
                write(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }.toString())
                write("\n")
                flush()
            }
        }
    }

    override fun close() {
        process?.destroy()
        scope.cancel()
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}

internal fun JsonElement.intValue() = jsonPrimitive.int
