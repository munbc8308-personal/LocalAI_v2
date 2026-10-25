package dev.vesta.storage

import dev.vesta.core.SessionId
import dev.vesta.core.plan.PlanCheck
import dev.vesta.core.plan.PlanItem
import dev.vesta.core.plan.PlanRepository
import dev.vesta.core.plan.PlanStatus
import dev.vesta.core.plan.TaskPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock

/** 작업 계획 (M32, v28) */
class ExposedPlanRepository(private val db: Database, private val clock: Clock = Clock.systemUTC()) : PlanRepository {
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    override suspend fun get(session: SessionId): TaskPlan? = io {
        TaskPlans.selectAll().where { TaskPlans.sessionId eq session.value }.firstOrNull()?.let { row ->
            TaskPlan(row[TaskPlans.goal], row[TaskPlans.items].lines().filter { it.isNotBlank() }.mapNotNull(::item), row[TaskPlans.updatedAt], row[TaskPlans.tainted])
        }
    }

    override suspend fun save(session: SessionId, plan: TaskPlan) = io {
        TaskPlans.upsert {
            it[sessionId] = session.value
            it[goal] = plan.goal.take(4_000)
            it[items] = plan.items.joinToString("\n") { i -> "${i.status.name}|${i.check.name}|${i.text.replace('\n', ' ')}" }
            it[updatedAt] = clock.instant()
            it[tainted] = plan.tainted
        }
        Unit
    }

    override suspend fun clear(session: SessionId) = io { TaskPlans.deleteWhere { TaskPlans.sessionId eq session.value }; Unit }

    private fun item(line: String): PlanItem? {
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val status = runCatching { PlanStatus.valueOf(parts[0]) }.getOrNull() ?: return null
        val check = runCatching { PlanCheck.valueOf(parts[1]) }.getOrDefault(PlanCheck.NONE)
        return PlanItem(parts[2], status, check)
    }
}
