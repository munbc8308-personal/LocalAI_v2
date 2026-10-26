package dev.vesta.agents

import dev.vesta.core.plan.PlanCheck
import dev.vesta.core.plan.PlanItem
import dev.vesta.core.plan.PlanStatus
import dev.vesta.core.plan.TaskPlan
import dev.vesta.core.tools.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 작업 계획 도구 `update_plan` (PROJECT.md 7.26 J-02·J-05, M32). 작업 에이전트 안에서 직접 처리한다(승인 없음, 대화의 계획만 바꾼다).
 * 빌드·테스트로 확인하는 항목은 [verified]가 참일 때만 done이 된다
 */
internal object PlanTool {
    const val NAME = "update_plan"

    val spec = ToolSpec(
        NAME,
        "작업의 할 일 목록을 만들거나 바꾼다. 항목 전체를 순서대로 보낸다. 두 단계 이상 걸리는 작업이면 먼저 만들고, 항목을 시작하면 in_progress, 끝나면 done으로 바꾼다. " +
            "빌드·테스트로 확인할 항목은 check를 build·test로 둔다 (실제로 성공한 기록이 있어야 done이 된다).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "array")
                    put("description", "할 일 항목들 (최대 ${TaskPlan.MAX_ITEMS}개)")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") { put("type", "string"); put("description", "할 일 한 줄") }
                            putJsonObject("status") { put("type", "string"); put("description", "pending / in_progress / done / skipped") }
                            putJsonObject("check") { put("type", "string"); put("description", "none / build / test (기본 none)") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("text")); add(JsonPrimitive("status")) }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("items")) }
        },
    )

    /** 결과: 새 계획(바꿀 수 없으면 null)과 모델에 돌려줄 글 */
    fun apply(old: TaskPlan?, goal: String, arguments: JsonObject, verified: (PlanCheck) -> String?): Pair<TaskPlan?, String> {
        val raw = (arguments["items"] as? JsonArray) ?: return null to "오류: items 배열이 필요함"
        if (raw.isEmpty() || raw.size > TaskPlan.MAX_ITEMS) return null to "오류: 항목은 1~${TaskPlan.MAX_ITEMS}개"
        val rejected = mutableListOf<String>()
        val items = raw.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val raw = (o["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.replace('\n', ' ') ?: return@mapNotNull null
            // 모델이 목록 화면 글의 "(확인: build)"까지 베껴 보내면 확인 방법이 빠져 빌드 없이 done이 됐다 (2차 시험): 떼어 내고 확인 방법으로 쓴다
            val suffix = SUFFIX.find(raw)
            val text = (if (suffix != null) raw.substring(0, suffix.range.first) else raw).trim().take(TaskPlan.MAX_TEXT).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            var status = (o["status"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { PlanStatus.valueOf(it.trim().uppercase()) }.getOrNull() } ?: PlanStatus.PENDING
            val previous = old?.items?.firstOrNull { it.text == text }?.check ?: PlanCheck.NONE
            val given = (o["check"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { PlanCheck.valueOf(it.trim().uppercase()) }.getOrNull() }
                ?: suffix?.let { PlanCheck.valueOf(it.groupValues[1].uppercase()) }
            // 있던 항목의 build·test 확인은 none으로 내릴 수 없다
            val check = if (given == null || given == PlanCheck.NONE && previous != PlanCheck.NONE) previous else given
            if (status == PlanStatus.DONE && check != PlanCheck.NONE) verified(check)?.let { why ->
                rejected += "\"$text\"은 done으로 못 바꿈: $why"
                status = PlanStatus.IN_PROGRESS
            }
            PlanItem(text, status, check)
        }
        if (items.isEmpty()) return null to "오류: 쓸 수 있는 항목이 없음 (text·status 필요)"
        // 끝나지 않은 build·test 항목은 글을 바꾸거나 빼서 없앨 수 없다: 그대로 다시 붙인다
        val kept = old?.items.orEmpty().filter { it.check != PlanCheck.NONE && it.status != PlanStatus.DONE && items.none { n -> n.text == it.text } }
        kept.forEach { rejected += "\"${it.text}\"은 빼거나 이름을 바꿀 수 없음: 실행 기록으로 확인되기 전까지 목록에 남는다" }
        val plan = TaskPlan(old?.goal ?: goal, (items + kept).take(TaskPlan.MAX_ITEMS))
        return plan to (plan.render() + if (rejected.isEmpty()) "" else "\n" + rejected.joinToString("\n") { "거부: $it. 실제로 실행해 성공시킨 뒤 다시 done으로 바꿔라." })
    }

    private val SUFFIX = Regex("\\s*\\(확인:\\s*(build|test|none)\\)\\s*$", RegexOption.IGNORE_CASE)
}
