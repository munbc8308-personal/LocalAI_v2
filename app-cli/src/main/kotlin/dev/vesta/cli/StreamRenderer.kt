package dev.vesta.cli

import dev.vesta.core.agent.AgentEvent
import java.io.PrintWriter
import kotlin.time.Duration

/** 대화 이벤트를 터미널에 그린다. 생각 과정은 기본으로 "생각 중…" 한 줄만 보여 준다. */
internal class StreamRenderer(private val out: PrintWriter, private val showThinking: Boolean) {
    private var thinkingShown = false
    private var answerStarted = false

    fun render(event: AgentEvent, elapsed: Duration) {
        when (event) {
            is AgentEvent.SessionReady, is AgentEvent.Status -> Unit
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
                out.print(event.text)
            }
            is AgentEvent.Done -> {
                clearThinkingIfNoAnswer()
                val tokens = event.usage?.completionTokens?.let { " · ${it} tok" }.orEmpty()
                out.println()
                out.println(dim("[${event.model}$tokens · ${"%.1f".format(elapsed.inWholeMilliseconds / 1000.0)}s]"))
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
