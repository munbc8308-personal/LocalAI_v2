package dev.vesta.mcp

import dev.vesta.core.config.McpServerConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** MCP 서버 하나의 도구 목록. 처음 필요할 때 서버를 띄워 받아 두고 재사용한다. */
class McpToolSource(
    val name: String,
    private val config: McpServerConfig,
    private val maxOutputChars: Int,
) : AutoCloseable {
    val client = McpStdioClient(name, listOf(config.command) + config.args)
    private val lock = Mutex()
    private var cached: List<McpTool>? = null

    suspend fun tools(): List<McpTool> = lock.withLock {
        cached ?: client.listTools()
            .map { McpTool(name, it, RiskMapping.of(it, config.toolOverrides), client, maxOutputChars) }
            .also { cached = it }
    }

    override fun close() = client.close()
}
