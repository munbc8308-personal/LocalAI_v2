package dev.vesta.storage

import dev.vesta.core.SessionId
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

class ExposedRoutingLogRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : RoutingLogRepository {
    override suspend fun record(sessionId: SessionId, decision: RoutingDecision) {
        withContext(Dispatchers.IO) {
            transaction(db) {
                RoutingLogs.insert {
                    it[RoutingLogs.sessionId] = sessionId.value
                    it[question] = decision.question.take(2_000)
                    it[agent] = decision.agent.name
                    it[requested] = decision.requested?.name
                    it[stage] = decision.stage.name
                    it[confidence] = decision.confidence
                    it[needsWeb] = decision.needsWeb
                    it[needsLocal] = decision.needsLocal
                    it[latencyMs] = decision.latencyMs
                    it[createdAt] = clock.instant()
                }
            }
        }
    }
}
