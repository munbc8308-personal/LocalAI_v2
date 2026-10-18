package dev.vesta.agents

/**
 * 모델 내부 표식이 답변에 새어 나오는 것을 걸러 낸다 (M6에서 gemma4가 도구 사용 중 가끔 "thought\n", "<channel|>"를 냄).
 * 응답 하나(턴)마다 새로 만들어 쓴다. 앞부분은 표식인지 알 수 있을 때까지 잠깐 모아 둔다.
 */
internal class ArtifactFilter {
    private val head = StringBuilder()
    private var started = false

    /** 내보낼 글자. 아직 판단 중이면 빈 문자열. */
    fun accept(piece: String): String {
        val cleaned = MARKER.replace(piece, "")
        if (started) return cleaned
        head.append(cleaned)
        val text = head.toString()
        if (text.length < LEADING.length + 1 && LEADING.startsWith(text.trimStart())) return ""
        started = true
        return if (text.trimStart().startsWith(LEADING)) text.trimStart().removePrefix(LEADING).trimStart('\n') else text
    }

    /** 턴이 끝날 때 남은 글자 */
    fun flush(): String = if (started) "" else head.toString().let { if (it.trim() == LEADING.trim()) "" else it }.also { started = true }

    private companion object {
        const val LEADING = "thought\n"
        val MARKER = Regex("<\\|?(channel|turn|start_of_turn|end_of_turn)\\|?>")
    }
}
