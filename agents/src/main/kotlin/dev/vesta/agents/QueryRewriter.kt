package dev.vesta.agents

import dev.vesta.core.ModelRole
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.model.ModelProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.fold
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

private val log = KotlinLogging.logger {}

/**
 * 웹 검색어 생성 (PROJECT.md 5.1 [1], K-09).
 * 대화 문맥을 반영해 독립적인 검색어를 만든다. 개인정보 원칙(5.4): **사용자 대화에서만** 만들고 로컬 문서 내용은 넣지 않는다.
 * 실패하면 질문 그대로 쓴다.
 */
class QueryRewriter(
    private val inference: InferenceClient,
    private val models: ModelProvider,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxQueries: Int = 2,
) {
    suspend fun webQueries(history: List<ChatMessage>): List<String> {
        val question = history.last().content
        val recent = history.dropLast(1).takeLast(4).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(300)}" }
        val prompt = """
            사용자 질문에 답하기 위한 웹 검색어를 최대 ${maxQueries}개 만든다.
            - 이전 대화를 참고해 대명사·생략을 풀어 혼자서도 뜻이 통하는 검색어로 쓴다.
            - 검색 엔진에 넣기 좋게 핵심 단어 위주로 짧게 쓴다. 기술 주제는 영어 검색어를 하나 섞어도 좋다.
            - 최신 정보가 필요하면 연도를 넣는다. 오늘은 ${LocalDate.now(clock.withZone(zone))}이다.
            - 질문에 없는 개인 정보는 넣지 않는다.
            JSON 한 줄로만 답한다: {"queries": ["검색어1", "검색어2"]}

            이전 대화:
            ${recent.ifBlank { "(없음)" }}

            질문: $question
        """.trimIndent()
        return runCatching {
            val spec = models.acquire(ModelRole.GENERAL)
            val text = inference.chat(
                ChatRequest(
                    model = spec.name,
                    messages = listOf(ChatMessage(ChatRole.USER, prompt)),
                    temperature = 0.0,
                    maxTokens = 200,
                    reasoningEffort = spec.reasoningEffort,
                    keepAlive = spec.keepAlive,
                ),
            ).filterIsInstance<ChatChunk.Delta>().fold(StringBuilder()) { acc, chunk -> acc.append(chunk.text) }.toString()
            parse(text).take(maxQueries).ifEmpty { listOf(question) }
        }.getOrElse {
            log.warn(it) { "검색어 생성 실패, 질문 그대로 사용" }
            listOf(question)
        }
    }

    companion object {
        /** 모델 답에서 첫 JSON 객체의 queries 배열을 꺼낸다. 코드 블록으로 감싸도 된다. */
        fun parse(text: String): List<String> {
            val json = text.substring(text.indexOf('{').takeIf { it >= 0 } ?: return emptyList(), text.lastIndexOf('}') + 1)
            return runCatching {
                Json.parseToJsonElement(json).jsonObject["queries"]!!.jsonArray
                    .map { it.jsonPrimitive.content.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }.getOrDefault(emptyList())
        }
    }
}
