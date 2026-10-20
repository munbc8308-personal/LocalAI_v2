package dev.vesta.agents

import dev.vesta.core.AgentType
import dev.vesta.core.ModelRole
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.media.ImageGenerator
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.MediaKind
import dev.vesta.core.media.MediaStore
import dev.vesta.core.model.ModelProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * 이미지 생성 에이전트 (PROJECT.md 4.8 ImageAgent, V-06).
 * 메인 모델이 한국어 요청(과 앞서 만든 이미지의 프롬프트)을 영어 프롬프트로 바꾸고, FLUX.1-schnell로 그린다.
 * 그리기 전에 이미지 모델 자리를 비우고(2.3 메모리 예산), 결과는 사용자 갤러리(MediaAsset)에 저장한다.
 */
class ImageAgent(
    private val inference: InferenceClient,
    private val models: ModelProvider,
    private val generator: ImageGenerator,
    private val media: MediaStore,
    /** 이미지 모델이 쓸 메모리만큼 자리를 비운다 (ModelManager.reserve) */
    private val reserve: suspend () -> Unit = {},
    private val size: Int = 1024,
    private val steps: Int = 4,
) : Agent {
    override val type = AgentType.IMAGE
    private val json = Json { ignoreUnknownKeys = true }

    /** 메인 모델이 정한 그림 설명과 비율 */
    internal data class Plan(val prompt: String, val aspect: String)

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        emit(AgentEvent.Status("그림 설명을 쓰는 중"))
        val plan = plan(request.history)
        val (width, height) = dimensions(plan.aspect)
        emit(AgentEvent.Status("이미지 그리는 중 (${generator.model})"))
        reserve()
        val image = try {
            generator.generate(plan.prompt, width, height, steps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            emit(AgentEvent.Token(e.message ?: "이미지를 만들지 못했습니다."))
            emit(AgentEvent.Done(type, generator.model, null))
            return@flow
        }
        val asset = media.save(request.user.userId, MediaKind.GENERATED, "image/png", image.png, plan.prompt)
        emit(AgentEvent.Media(asset, image.png, plan.prompt))
        // 다음 요청("더 밝게")에서 이 프롬프트를 고쳐 쓸 수 있게 답변에 남긴다
        emit(AgentEvent.Token("이미지를 만들었습니다 (${width}×${height}, AI 생성).\n\n프롬프트: `${plan.prompt}`"))
        emit(AgentEvent.Done(type, generator.model, null))
    }

    /** 대화(요청과 이전 프롬프트)로 영어 프롬프트를 만든다. 실패하면 요청 그대로 */
    internal suspend fun plan(history: List<ChatMessage>): Plan {
        val question = history.last().content
        val recent = history.dropLast(1).takeLast(6).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(400)}" }
        val prompt = """
            You write prompts for the FLUX image model. Read the user's latest request (often Korean) and the recent conversation.
            - Output ONE English prompt (max 80 words) describing exactly what to draw: subject, style, composition, lighting, colors.
            - If the user asks to change an image made earlier ("더 밝게", "고양이로 바꿔"), start from the previous prompt in the conversation and apply the change.
            - Keep any text the user wants written in the image in quotes.
            - aspect: "portrait" for tall images (세로, 포스터, 휴대폰 배경), "landscape" for wide ones (가로, 풍경, 배너), otherwise "square".
            Reply with one JSON line only: {"prompt": "...", "aspect": "square|portrait|landscape"}

            Recent conversation:
            ${recent.ifBlank { "(none)" }}

            Latest request: $question
        """.trimIndent()
        return try {
            val spec = models.acquire(ModelRole.GENERAL)
            val text = inference.chat(
                ChatRequest(
                    model = spec.name,
                    messages = listOf(ChatMessage(ChatRole.USER, prompt)),
                    temperature = 0.3,
                    maxTokens = 300,
                    reasoningEffort = spec.reasoningEffort,
                    keepAlive = spec.keepAlive,
                ),
            ).filterIsInstance<ChatChunk.Delta>().fold(StringBuilder()) { acc, chunk -> acc.append(chunk.text) }.toString()
            parse(text) ?: Plan(question, "square")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "이미지 프롬프트 작성 실패, 요청 그대로 사용" }
            Plan(question, "square")
        }
    }

    internal fun parse(text: String): Plan? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Plan(prompt.take(1_500), obj["aspect"]?.jsonPrimitive?.contentOrNull ?: "square")
    }

    /** FLUX는 16의 배수 크기. 세로·가로는 3:4·4:3 */
    internal fun dimensions(aspect: String): Pair<Int, Int> {
        val short = (size * 3 / 4) / 16 * 16
        return when (aspect) {
            "portrait" -> short to size
            "landscape" -> size to short
            else -> size to size
        }
    }
}
