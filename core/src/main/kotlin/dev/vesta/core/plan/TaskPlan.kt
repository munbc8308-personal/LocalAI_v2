package dev.vesta.core.plan

import dev.vesta.core.SessionId
import java.time.Instant

enum class PlanStatus { PENDING, IN_PROGRESS, DONE, SKIPPED }

/** 항목이 끝났는지 무엇으로 확인하나 (J-05): build·test면 실행 기록으로만 done */
enum class PlanCheck { NONE, BUILD, TEST }

data class PlanItem(val text: String, val status: PlanStatus = PlanStatus.PENDING, val check: PlanCheck = PlanCheck.NONE)

/**
 * 작업 계획 (PROJECT.md 7.26, M32): 대화 하나에 하나. [goal]은 처음 작업 요청 글
 */
data class TaskPlan(
    val goal: String,
    val items: List<PlanItem>,
    val updatedAt: Instant = Instant.EPOCH,
    /** 로컬 데이터를 읽은 뒤(오염된 실행에서) 만들거나 바꾼 계획: 이어 가는 실행도 처음부터 인터넷 도구에 승인을 받는다 (보안 검토) */
    val tainted: Boolean = false,
) {
    val remaining: List<PlanItem> get() = items.filter { it.status == PlanStatus.PENDING || it.status == PlanStatus.IN_PROGRESS }
    val done: Int get() = items.count { it.status == PlanStatus.DONE || it.status == PlanStatus.SKIPPED }

    /** `[x] 글 (build)` 꼴 목록 */
    fun render(): String = items.joinToString("\n") { item ->
        val mark = when (item.status) { PlanStatus.DONE -> "[x]"; PlanStatus.IN_PROGRESS -> "[~]"; PlanStatus.SKIPPED -> "[-]"; PlanStatus.PENDING -> "[ ]" }
        "$mark ${item.text}" + if (item.check != PlanCheck.NONE) " (확인: ${item.check.name.lowercase()})" else ""
    }

    /** "할 일 3/7 · 지금: …" */
    fun progress(): String = "할 일 $done/${items.size}" + ((items.firstOrNull { it.status == PlanStatus.IN_PROGRESS } ?: remaining.firstOrNull())?.let { " · 지금: ${it.text}" } ?: " · 모두 끝남")

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_TEXT = 200
    }
}

interface PlanRepository {
    suspend fun get(session: SessionId): TaskPlan?
    suspend fun save(session: SessionId, plan: TaskPlan)
    suspend fun clear(session: SessionId)
}
