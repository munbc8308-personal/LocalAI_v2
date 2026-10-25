package dev.vesta.agents

import dev.vesta.core.tools.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 글로 새어 나온 도구 호출 살리기 (M30에서 발견). gemma4가 가끔 도구 호출을 구조화된 호출 대신 글로 낸다:
 * `search_conversations{query:<|"|>파이썬 정렬<|"|>}<tool_call|>` (앞의 `<|tool_call>call:`은 있거나 없음).
 * - 턴 첫머리가 이 꼴일 수 있는 동안은 글을 내보내지 않고 모았다가, 맞으면 [call]로 바꾼다
 * - 설명 글 **뒤에** `call:이름{…}`가 나오는 경우도 있다(가계부 앱 시험): 그건 이미 내보냈으므로 [scan]이 턴 끝에 원문에서 찾는다
 */
internal class LeakedToolCall(private val names: Set<String>) {
    private val head = StringBuilder()
    /** 이 턴에 받은 원문 전체 (따옴표 표시 `<|"|>`를 바꾸기 전) */
    private val raw = StringBuilder()
    private var decided = false
    private var holding = false
    var call: ToolCall? = null
        private set

    /** 내보낼 글자 (아직 판단 중이면 빈 문자열) */
    fun accept(piece: String): String {
        raw.append(piece)
        if (decided) return if (holding) { head.append(piece); "" } else clean(piece)
        head.append(piece)
        val t = head.toString().trimStart().removePrefix("<|tool_call>").removePrefix("call:")
        val name = t.substringBefore('{', missingDelimiterValue = t)
        if (names.any { it.startsWith(name) } && (t.isEmpty() || !t.contains('{') && name.all { it.isLetterOrDigit() || it == '_' })) return ""
        decided = true
        if (name in names && t.contains('{')) { holding = true; return "" }
        return clean(head.toString())
    }

    /** 턴이 끝날 때: 모은 것이 호출이면 [call]에 두고 빈 글, 아니면 남은 글 */
    fun flush(): String {
        val text = head.toString()
        if (!holding && decided) return ""
        decided = true
        call = PATTERN.find(text.trim())?.let { m -> m.groupValues[1].takeIf { it in names }?.let { name -> toCall(name, m.groupValues[2]) } }
        return if (call != null) "" else clean(text)
    }

    /**
     * 설명 글 사이에 `call:이름{…}`로 새어 나온 호출들 (아는 도구만, 순서대로).
     * 코드 블록(```)·인용(>) 안의 것은 예시나 읽은 글의 인용이라 뺀다. 이렇게 살린 호출은 [INLINE_ID]로 시작해 TaskAgent가 읽기 말고는 승인을 받는다 (보안 검토)
     */
    fun scan(): List<ToolCall> {
        val text = masked(raw.toString())
        val calls = mutableListOf<ToolCall>()
        var from = 0
        while (true) {
            val m = INLINE.find(text, from) ?: break
            val name = m.groupValues[1]
            val open = m.range.last
            val close = closing(text, open) ?: break
            if (name in names) toCall(name, text.substring(open + 1, close), INLINE_ID)?.let { calls += it }
            from = close + 1
        }
        return calls
    }

    /** [open]의 `{`에 맞는 `}` 위치. `<|"|>…<|"|>` 안의 괄호는 세지 않는다 */
    private fun closing(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text.startsWith(Q, i)) {
                val end = text.indexOf(Q, i + Q.length).takeIf { it >= 0 } ?: return null
                i = end + Q.length
                continue
            }
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** 코드 블록·인용 줄을 같은 길이의 공백으로 (위치는 그대로 두고 찾지 않게) */
    private fun masked(text: String): String {
        val out = StringBuilder(text)
        Regex("```.*?(```|$)", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m -> for (i in m.range) if (out[i] != '\n') out[i] = ' ' }
        Regex("(?m)^\\s*>.*$").findAll(text).forEach { m -> for (i in m.range) out[i] = ' ' }
        return out.toString()
    }

    /** gemma 꼴 인자(`key:<|"|>값<|"|>,n:5`) → JSON. 문자열 안은 그대로 두고, 밖의 키에만 따옴표를 붙인다 */
    private fun toCall(name: String, body: String, prefix: String = "leaked-"): ToolCall? {
        val out = StringBuilder("{")
        var i = 0
        var expectKey = true
        while (i < body.length) {
            when {
                body.startsWith(Q, i) -> {
                    val end = body.indexOf(Q, i + Q.length).takeIf { it >= 0 } ?: return null
                    out.append(JsonPrimitive(body.substring(i + Q.length, end)).toString())
                    i = end + Q.length
                    expectKey = false
                }
                expectKey && (body[i].isLetter() || body[i] == '_') -> {
                    val colon = body.indexOf(':', i).takeIf { it > i } ?: return null
                    val key = body.substring(i, colon).trim()
                    if (!key.all { it.isLetterOrDigit() || it == '_' }) return null
                    out.append('"').append(key).append("\":")
                    i = colon + 1
                    expectKey = false
                }
                else -> {
                    val c = body[i]
                    out.append(c)
                    if (c == ',' || c == '{') expectKey = true else if (!c.isWhitespace()) expectKey = false
                    i++
                }
            }
        }
        out.append('}')
        val args = runCatching { Json.parseToJsonElement(out.toString()) as JsonObject }.getOrNull() ?: return null
        return ToolCall(prefix + System.nanoTime(), name, args.toString())
    }

    private fun clean(text: String) = text.replace(Q, "\"").replace(END, "")

    companion object {
        /** 설명 글에서 살린 호출의 ID 머리 */
        const val INLINE_ID = "leaked-inline-"
        private const val Q = "<|\"|>"
        private val PATTERN = Regex("^(?:<\\|tool_call>)?(?:call:)?([A-Za-z0-9_]+)\\{(.*)}\\s*(?:<tool_call\\|>)?\\s*$", RegexOption.DOT_MATCHES_ALL)
        private val INLINE = Regex("(?:<\\|tool_call>)?call:([A-Za-z0-9_]+)\\{")
        private val END = Regex("<\\|?tool_call\\|?>")
    }
}
