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
 * 요청에 이미지를 붙였으면 그 그림을 기준으로 그린다 (V-11, img2img): 메인 모델이 그림을 보고 프롬프트·기준을 남길 정도를 정한다.
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

    /** 메인 모델이 정한 그림 설명과 비율. 기준 그림이 있으면 [keep](기준을 남길 정도), 거절이면 [refuse] */
    internal data class Plan(val prompt: String, val aspect: String, val keep: Double = KEEP_MEDIUM, val refuse: String? = null)

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        // 붙인 첫 그림을 기준으로 (V-11)
        val reference = request.history.last().images.firstOrNull()
        emit(AgentEvent.Status(if (reference != null) "기준 그림을 보고 그림 설명을 쓰는 중" else "그림 설명을 쓰는 중"))
        val plan = plan(request.history, reference?.let { ImageFit.aspect(it.bytes) ?: "square" })
        plan.refuse?.let {
            emit(AgentEvent.Token("이 그림은 만들지 않습니다: $it"))
            emit(AgentEvent.Done(type, generator.model, null))
            return@flow
        }
        val (width, height) = dimensions(plan.aspect)
        // 만들 크기에 맞춰 가운데를 자른다 (mflux는 늘여서 맞춘다). 못 읽는 형식(WebP 등)이면 그대로
        val base = reference?.let { ImageFit.cover(it.bytes, width, height) ?: it.bytes }
        emit(AgentEvent.Status("이미지 그리는 중 (${generator.model}${if (base != null) ", 기준 그림" else ""})"))
        reserve()
        val image = try {
            generator.generate(plan.prompt, width, height, if (base != null) REFERENCE_STEPS else steps, reference = base, keep = plan.keep)
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
        val basis = if (base != null) ", 붙인 그림 기준 · ${keepName(plan.keep)}" else ""
        emit(AgentEvent.Token("이미지를 만들었습니다 (${width}×${height}$basis, AI 생성).\n\n프롬프트: `${plan.prompt}`" +
            if (base != null) "\n\n붙인 그림은 구도·색의 기준으로 씁니다. 원본에 더 가깝게는 \"비슷하게\", 더 바꾸려면 \"자유롭게\"를 붙여 다시 요청하세요." else ""))
        emit(AgentEvent.Done(type, generator.model, null))
    }

    /**
     * 대화(요청과 이전 프롬프트)로 영어 프롬프트를 만든다. 실패하면 요청 그대로.
     * [referenceAspect]가 있으면 붙인 그림을 메인 모델에게 함께 보여 준다 (비전)
     */
    internal suspend fun plan(history: List<ChatMessage>, referenceAspect: String? = null): Plan {
        val question = history.last().content
        val recent = history.dropLast(1).takeLast(6).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(400)}" }
        val referenceRule = if (referenceAspect != null) """
            - The user attached a reference image (shown to you). The new image is generated FROM it (image-to-image), so describe the result you want:
              keep the reference's subject and composition unless the request changes them, and apply the requested style or change.
            - "keep": how closely to follow the reference's composition and colors. "high" for a close variation (원본 느낌, 살짝, 비슷하게),
              "low" when the user wants a big change of content or style (자유롭게, 분위기만, 참고만, 다른 화풍으로), otherwise "medium".
            - aspect: "$referenceAspect" (the reference's shape) unless the user asks for another shape.
            - If the reference shows a real person and the request would put them in sexual, humiliating, violent or deceptive situations
              (fake news photo, impersonation), set "refuse" to a short Korean reason. Style changes (illustration, background, lighting) are fine.
        """.trimIndent() else ""
        val prompt = """
            You write prompts for the FLUX image model. Read the user's latest request (often Korean) and the recent conversation.
            - Output ONE English prompt (max 80 words) describing exactly what to draw: subject, style, composition, lighting, colors.
            - If the user asks to change an image made earlier ("더 밝게", "고양이로 바꿔"), start from the previous prompt in the conversation and apply the change.
            - Keep any text the user wants written in the image in quotes.
            - aspect: "portrait" for tall images (세로, 포스터, 휴대폰 배경), "landscape" for wide ones (가로, 풍경, 배너), otherwise "square".
            $referenceRule
            Reply with one JSON line only: {"prompt": "...", "aspect": "square|portrait|landscape"${if (referenceAspect != null) """, "keep": "low|medium|high", "refuse": null""" else ""}}

            Recent conversation:
            ${recent.ifBlank { "(none)" }}

            Latest request: $question
        """.trimIndent()
        return try {
            val spec = models.acquire(ModelRole.GENERAL)
            val text = inference.chat(
                ChatRequest(
                    model = spec.name,
                    // 기준 그림은 메인 모델(비전)이 보고 프롬프트를 쓴다
                    messages = listOf(ChatMessage(ChatRole.USER, prompt, images = if (referenceAspect != null) history.last().images.take(1) else emptyList())),
                    temperature = 0.3,
                    maxTokens = 300,
                    reasoningEffort = spec.reasoningEffort,
                    keepAlive = spec.keepAlive,
                ),
            ).filterIsInstance<ChatChunk.Delta>().fold(StringBuilder()) { acc, chunk -> acc.append(chunk.text) }.toString()
            parse(text) ?: Plan(question, referenceAspect ?: "square")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "이미지 프롬프트 작성 실패, 요청 그대로 사용" }
            Plan(question, referenceAspect ?: "square")
        }
    }

    internal fun parse(text: String): Plan? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        fun str(key: String) = obj[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val refuse = str("refuse")
        val prompt = str("prompt") ?: if (refuse != null) "" else return null
        val keep = when (str("keep")) { "high" -> KEEP_HIGH; "low" -> KEEP_LOW; else -> KEEP_MEDIUM }
        return Plan(prompt.take(1_500), str("aspect") ?: "square", keep, refuse)
    }

    private fun keepName(keep: Double) = when {
        keep >= KEEP_HIGH -> "원본과 비슷하게"
        keep <= KEEP_LOW -> "구도·색만 참고"
        else -> "구도·색을 따라감"
    }

    companion object {
        /**
         * 기준 그림을 남길 정도 (mflux image_strength: [REFERENCE_STEPS]단계 중 앞 단계를 이만큼 건너뛴다).
         * FLUX.1-schnell은 0.5만 넘어도 원본과 거의 같고, 가장 낮은 1/8에서도 화풍(수채화 등)은 거의 바뀌지 않는다 (13.56 측정)
         */
        const val KEEP_LOW = 0.125
        const val KEEP_MEDIUM = 0.25
        const val KEEP_HIGH = 0.375
        /** 기준 그림이 있으면 8단계 (남길 정도를 1/8씩 고를 수 있게, 1024×576 약 8초) */
        const val REFERENCE_STEPS = 8
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
