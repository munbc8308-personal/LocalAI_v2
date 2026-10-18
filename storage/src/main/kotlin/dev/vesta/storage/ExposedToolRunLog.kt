package dev.vesta.storage

import dev.vesta.core.RiskLevel
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.tools.ToolResult
import dev.vesta.core.tools.ToolRunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

class ExposedToolRunLog(private val db: Database, private val clock: Clock = Clock.systemUTC()) : ToolRunLog {
    override suspend fun record(
        user: UserContext,
        sessionId: SessionId,
        tool: String,
        arguments: String,
        risk: RiskLevel,
        approved: Boolean?,
        result: ToolResult,
        durationMs: Long,
    ) {
        withContext(Dispatchers.IO) {
            transaction(db) {
                ToolRuns.insert {
                    it[userId] = user.userId.value
                    it[ToolRuns.sessionId] = sessionId.value
                    it[ToolRuns.tool] = tool
                    it[ToolRuns.arguments] = arguments.take(20_000)
                    it[ToolRuns.risk] = risk.name
                    it[ToolRuns.approved] = approved
                    it[isError] = result.isError
                    it[ToolRuns.result] = result.output.take(4_000)
                    it[ToolRuns.durationMs] = durationMs
                    it[createdAt] = clock.instant()
                }
            }
        }
    }
}
