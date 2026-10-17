package dev.vesta.router

import dev.vesta.core.AgentType
import dev.vesta.core.ModelRole
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.model.ModelProvider
import dev.vesta.decision.RoutingQuestions
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.fold
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LlmVerdict(val agent: AgentType, val confidence: Double, val needsWeb: Boolean, val needsLocal: Boolean)

/**
 * 라우팅 4단계: 메인 모델이 JSON으로 판정 (PROJECT.md 4.5).
 * M4 평가(eval/routing-ko.tsv): gemma4:26b-mxfp8로 D1 69/70(99%), 약 275ms.
 * 직전 대화를 함께 줘서 "그거 더 자세히" 같은 이어지는 질문도 판정한다.
 */
class LlmRouter(
    private val inference: InferenceClient,
    private val models: ModelProvider,
) {
    suspend fun judge(question: String, recent: List<ChatMessage>): LlmVerdict? {
        val spec = models.acquire(ModelRole.GENERAL)
        val context = recent.takeLast(4).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(200)}" }
        val prompt = buildString {
            appendLine("사용자 요청을 분류한다. JSON 한 줄로만 답한다.")
            appendLine("agent 값:")
            RoutingQuestions.descriptionsKo.forEach { (agent, description) -> appendLine("- ${agent.name}: $description") }
            appendLine("needsWeb: 인터넷 최신 정보가 필요하면 true")
            appendLine("needsLocal: 사용자가 저장한 자기 문서나 코드 저장소가 필요하면 true")
            appendLine("""형식: {"agent": "CHAT", "confidence": 0.0~1.0, "needsWeb": false, "needsLocal": false}""")
            if (context.isNotBlank()) appendLine("\n직전 대화 (이어지는 질문이면 참고):\n$context")
            append("\n요청: ").append(question)
        }
        val text = inference.chat(
            ChatRequest(
                model = spec.name,
                messages = listOf(ChatMessage(ChatRole.USER, prompt)),
                temperature = 0.0,
                maxTokens = 80,
                reasoningEffort = spec.reasoningEffort,
                keepAlive = spec.keepAlive,
            ),
        ).filterIsInstance<ChatChunk.Delta>().fold(StringBuilder()) { acc, chunk -> acc.append(chunk.text) }.toString()
        return parse(text)
    }

    companion object {
        fun parse(text: String): LlmVerdict? = runCatching {
            val obj = Json.parseToJsonElement(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).jsonObject
            LlmVerdict(
                agent = AgentType.valueOf(obj.getValue("agent").jsonPrimitive.content.trim().uppercase()),
                confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
                needsWeb = obj["needsWeb"]?.jsonPrimitive?.booleanOrNull ?: false,
                needsLocal = obj["needsLocal"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }
}
