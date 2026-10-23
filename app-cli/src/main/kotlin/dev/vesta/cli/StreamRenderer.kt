package dev.vesta.cli

import dev.vesta.core.agent.AgentEvent
import java.io.PrintWriter
import kotlin.time.Duration

/** 대화 이벤트를 터미널에 그린다. 생각 과정은 기본으로 "생각 중…" 한 줄만 보여 준다. */
internal class StreamRenderer(private val out: PrintWriter, private val showThinking: Boolean) {
    private var thinkingShown = false
    private var sources: List<dev.vesta.core.knowledge.Evidence> = emptyList()
    private var grounding: AgentEvent.Grounding? = null
    private var answerStarted = false
    private val answer = StringBuilder()

    fun render(event: AgentEvent, elapsed: Duration) {
        when (event) {
            is AgentEvent.SessionReady -> Unit
            is AgentEvent.Routed -> out.println(dim("→ " + RoutingText.describe(event.decision)))
            is AgentEvent.Status -> if (event.text != "생성 중") {
                if (answerStarted) { out.println(); answerStarted = false }
                out.println(dim("· ${event.text}"))
            }
            is AgentEvent.Sources -> sources = event.evidence
            is AgentEvent.Grounding -> grounding = event
            // 파일 저장은 ChatCommand가 한다 (원격 모드면 서버에서 받아야 함)
            is AgentEvent.Media -> Unit
            // 기다렸다가 받는 것은 ChatCommand·PrintMode가 한다
            is AgentEvent.JobStarted -> Unit
            is AgentEvent.Saved -> Unit
            is AgentEvent.ToolStarted -> {
                if (answerStarted) out.println()
                answerStarted = false
                out.println(dim("  ⚙ ${event.tool} · ${event.summary}"))
            }
            is AgentEvent.ToolFinished -> out.println(dim("    ${if (event.ok) "✓" else "✗"} ${event.summary}"))
            // 승인 화면은 ChatCommand가 입력을 받아야 하므로 거기서 그린다
            is AgentEvent.ApprovalRequested -> Unit
            is AgentEvent.Reasoning -> {
                if (showThinking) {
                    out.print(dim(event.text))
                } else if (!thinkingShown) {
                    out.print(dim("생각 중…"))
                }
                thinkingShown = true
            }
            is AgentEvent.Token -> {
                if (!answerStarted) {
                    if (thinkingShown) out.print(if (showThinking) "\n\n" else CLEAR_LINE)
                    answerStarted = true
                }
                answer.append(event.text)
                out.print(event.text)
            }
            is AgentEvent.Done -> {
                clearThinkingIfNoAnswer()
                val tokens = event.usage?.completionTokens?.let { " · ${it} tok" }.orEmpty()
                out.println()
                grounding?.let { g ->
                    if (g.unsupported.isEmpty()) out.println(dim("근거 확인: ${g.checked}문장 모두 자료로 뒷받침됨"))
                    else {
                        out.println(red("⚠ 근거로 확인되지 않은 문장 ${g.unsupported.size}/${g.checked}:"))
                        g.unsupported.forEach { out.println(red("  · ${it.take(120)}")) }
                    }
                }
                // 답변에서 실제로 인용한 번호만 보여 준다
                val cited = Citations.cited(answer.toString(), sources)
                if (cited.isNotEmpty()) {
                    out.println(dim("출처"))
                    cited.forEach { out.println(dim("  [${it.citation}] ${it.title}${it.location?.let { l -> " · $l" }.orEmpty()} — ${it.ref}")) }
                } else if (sources.isNotEmpty()) {
                    out.println(dim("(인용한 출처 없음 · 검색 ${sources.size}건)"))
                }
                // 모델을 쓰지 않은 안내 답변(model "-")은 꼬리표를 달지 않는다
                if (event.model != "-") out.println(dim("[${event.model}$tokens · ${"%.1f".format(elapsed.inWholeMilliseconds / 1000.0)}s]"))
            }
            is AgentEvent.Error -> {
                clearThinkingIfNoAnswer()
                if (answerStarted) out.println()
                out.println(red("오류: ${event.message}"))
            }
        }
        out.flush()
    }

    fun interrupted() {
        clearThinkingIfNoAnswer()
        out.println(dim(if (answerStarted) "\n(중단됨)" else "(중단됨)"))
        out.flush()
    }

    private fun clearThinkingIfNoAnswer() {
        if (thinkingShown && !answerStarted && !showThinking) out.print(CLEAR_LINE)
    }

    private companion object {
        const val CLEAR_LINE = "\r\u001b[2K"
    }
}

internal fun dim(text: String) = "\u001b[2m$text\u001b[0m"
internal fun bold(text: String) = "\u001b[1m$text\u001b[0m"
internal fun red(text: String) = "\u001b[31m$text\u001b[0m"
