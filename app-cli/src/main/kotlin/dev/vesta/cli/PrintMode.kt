package dev.vesta.cli

import dev.vesta.app.AppContainer
import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.tools.ApprovalDecision
import java.io.PrintWriter
import kotlin.time.TimeSource

/**
 * 한 번만 묻고 끝내기 (`vesta -p "질문"`, PROJECT.md 7.14, M17). 작업 폴더·VESTA.md는 대화와 같다.
 * - 터미널이면 대화처럼 진행 상황·출처까지 보여 주고, 파이프·파일로 받으면 답만 내보낸다 (스크립트에서 쓰기 좋게)
 * - 승인: 파일 쓰기는 [acceptWrites]면 바로 승인. 그 밖(셸 포함)은 터미널에서 묻고, 터미널이 아니면 거부한다
 * 오류로 끝나면 종료 코드 1
 */
internal object PrintMode {
    suspend fun run(question: String, acceptWrites: Boolean, local: Boolean, useWorkspace: Boolean): Int {
        // JDK 21: 입출력이 터미널일 때만 null이 아니다
        val console = System.console()
        val backend = openBackend(local)
        val workspace = if (useWorkspace) Workspaces.current() else null
        val out = PrintWriter(System.out, true)
        val err = PrintWriter(System.err, true)
        val renderer = if (console != null) StreamRenderer(out, false) else null
        val start = TimeSource.Monotonic.markNow()
        var failed = false
        backend.use {
            backend.send(null, question, workspace).collect { event ->
                renderer?.render(event, start.elapsedNow()) ?: when (event) {
                    is AgentEvent.Token -> { out.print(event.text); out.flush() }
                    is AgentEvent.Done -> out.println()
                    is AgentEvent.Error -> err.println(event.message)
                    else -> Unit
                }
                if (event is AgentEvent.Error) failed = true
                if (event is AgentEvent.ApprovalRequested) {
                    val decision = when {
                        acceptWrites && event.risk == RiskLevel.WRITE -> ApprovalDecision.Approve.also { err.println("✓ 자동 승인 (파일 쓰기): ${event.details.title}".let { if (console != null) dim(it) else it }) }
                        console != null -> ask(console, err, event)
                        else -> ApprovalDecision.Deny("터미널이 아니라 승인할 수 없음").also {
                            err.println("거부: ${event.details.title} (터미널에서 실행하거나 파일 쓰기면 --accept-writes)")
                        }
                    }
                    backend.resolveApproval(event.requestId, decision)
                }
            }
        }
        return if (failed) 1 else 0
    }

    private fun ask(console: java.io.Console, err: PrintWriter, event: AgentEvent.ApprovalRequested): ApprovalDecision {
        err.println("┌ " + if (event.risk == RiskLevel.EXEC) red("셸 실행 — 매번 승인") else bold("쓰기 작업 — 승인 필요"))
        err.println("│ ${event.details.title}")
        event.details.body.takeIf { it.isNotBlank() }?.lines()?.take(25)?.forEach { err.println(dim("│ $it")) }
        event.details.warning?.let { err.println("│ " + red("⚠ $it")) }
        val answer = console.readLine("└ [y] 승인  [n] 거부 (기본 n) ")?.trim()?.lowercase()
        return if (answer == "y" || answer == "yes" || answer == "ㅛ") ApprovalDecision.Approve else ApprovalDecision.Deny("사용자가 거부함")
    }

    /** 서버가 떠 있으면 원격, 아니면 임베디드 (대화와 같다) */
    private suspend fun openBackend(local: Boolean): ChatBackend {
        if (!local) {
            val config = dev.vesta.core.config.ConfigLoader.load(dev.vesta.core.config.VestaPaths.default().configFile)
            RemoteBackend.connect(config.server.baseUrl, dev.vesta.app.ApiToken.read())?.let { return it }
        }
        return EmbeddedBackend(AppContainer())
    }
}
