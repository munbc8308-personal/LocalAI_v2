package dev.vesta.cli

import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.tools.ApprovalDecision
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import java.io.PrintWriter

/** CLI 승인 화면 (PROJECT.md T-03, T-07). 기본값은 거부다. */
internal object Approvals {
    fun ask(reader: LineReader, out: PrintWriter, request: AgentEvent.ApprovalRequested): ApprovalDecision {
        val level = if (request.risk == RiskLevel.EXEC) red("셸 실행 — 매번 승인") else bold("쓰기 작업 — 승인 필요")
        out.println()
        out.println("┌ $level")
        out.println("│ ${request.details.title}")
        request.details.body.takeIf { it.isNotBlank() }?.lines()?.take(25)?.forEach { out.println(dim("│ $it")) }
        request.details.warning?.let { out.println("│ " + red("⚠ $it")) }
        out.println("└ " + if (request.editableField != null) "[y] 승인  [n] 거부  [e] 명령 수정 후 승인" else "[y] 승인  [n] 거부")
        out.flush()
        val answer = try {
            reader.readLine("승인? (기본 n) ").trim().lowercase()
        } catch (_: UserInterruptException) {
            "n"
        } catch (_: EndOfFileException) {
            "n"
        }
        return when {
            answer == "y" || answer == "yes" || answer == "ㅛ" -> ApprovalDecision.Approve
            answer == "e" && request.editableField != null -> {
                val edited = try {
                    reader.readLine("새 명령: ", null, request.editableValue.orEmpty()).trim()
                } catch (_: Exception) {
                    ""
                }
                if (edited.isEmpty()) ApprovalDecision.Deny("수정 취소")
                else ApprovalDecision.ApproveEdited(buildJsonObject { put(request.editableField!!, JsonPrimitive(edited)) })
            }
            else -> ApprovalDecision.Deny("사용자가 거부함")
        }
    }
}
