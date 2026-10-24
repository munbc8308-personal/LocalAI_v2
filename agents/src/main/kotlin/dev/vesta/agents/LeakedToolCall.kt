package dev.vesta.agents

import dev.vesta.core.tools.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 글로 새어 나온 도구 호출 살리기 (M30에서 발견). gemma4가 가끔 도구 호출을 구조화된 호출 대신 글로 낸다:
 * `search_conversations{query:<|"|>파이썬 정렬<|"|>}<tool_call|>` (앞의 `<|tool_call>call:`은 있거나 없음).
 * 턴 첫머리가 이 꼴일 수 있는 동안은 글을 내보내지 않고 모았다가, 이 꼴이 맞으면 호출로 바꾸고 아니면 그대로 내보낸다
 */
internal class LeakedToolCall(private val names: Set<String>) {
    private val head = StringBuilder()
    private var decided = false
    private var holding = false
    var call: ToolCall? = null
        private set

    /** 내보낼 글자 (아직 판단 중이면 빈 문자열) */
    fun accept(piece: String): String {
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
        call = parse(text)
        return if (call != null) "" else clean(text)
    }

    private fun parse(text: String): ToolCall? {
        val m = PATTERN.find(text.trim()) ?: return null
        val name = m.groupValues[1].takeIf { it in names } ?: return null
        val body = STRING.replace(m.groupValues[2]) { JsonPrimitive(it.groupValues[1]).toString() }
            .replace(KEY) { "${it.groupValues[1]}\"${it.groupValues[2]}\":" }
        val args = runCatching { Json.parseToJsonElement("{$body}") as JsonObject }.getOrNull() ?: return null
        return ToolCall("leaked-" + System.nanoTime(), name, args.toString())
    }

    private fun clean(text: String) = text.replace(QUOTE, "\"").replace(END, "")

    private companion object {
        val PATTERN = Regex("^(?:<\\|tool_call>)?(?:call:)?([A-Za-z0-9_]+)\\{(.*)}\\s*(?:<tool_call\\|>)?\\s*$", RegexOption.DOT_MATCHES_ALL)
        val STRING = Regex("<\\|\"\\|>(.*?)<\\|\"\\|>", RegexOption.DOT_MATCHES_ALL)
        val KEY = Regex("(^|[,{]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:")
        val QUOTE = Regex("<\\|\"\\|>")
        val END = Regex("<\\|?tool_call\\|?>")
    }
}
