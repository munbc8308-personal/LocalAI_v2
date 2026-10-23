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
import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.ImageGenerator
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.MediaKind
import dev.vesta.core.media.MediaStore
import dev.vesta.core.media.Motion
import dev.vesta.core.media.TextToSpeech
import dev.vesta.core.media.VideoComposer
import dev.vesta.core.media.VideoScene
import dev.vesta.core.model.ModelProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * 영상 제작 에이전트 (PROJECT.md 7.18 G-01·G-02·G-04·G-06, M24).
 * 메인 모델이 대본을 장면으로 나누고(내레이션 한국어, 그림 설명 영어, 카메라 움직임), 장면마다 FLUX로 그림을 그리고 내레이션을 읽힌 뒤
 * ffmpeg로 합친다(자막은 내레이션 그대로). 실제 인물의 얼굴·목소리를 흉내 내는 요청은 만들지 않는다. 결과는 갤러리(MediaAsset VIDEO)에 남긴다.
 * 이미지 모델 자리는 그리기 전에 비운다(ModelManager.reserve). 메인 모델은 계획에만 쓴다
 */
class VideoAgent(
    private val inference: InferenceClient,
    private val models: ModelProvider,
    private val generator: ImageGenerator,
    private val composer: VideoComposer,
    private val media: MediaStore,
    /** 없으면 내레이션 없이 자막만 */
    private val speech: TextToSpeech? = null,
    private val reserve: suspend () -> Unit = {},
    /** 사용자가 넣은 배경 음악 (없으면 null) */
    private val music: suspend () -> ByteArray? = { null },
    private val steps: Int = 4,
) : Agent {
    override val type = AgentType.VIDEO
    private val json = Json { ignoreUnknownKeys = true }

    internal data class Scene(val narration: String, val image: String, val motion: Motion)
    internal data class Plan(val title: String, val aspect: String, val scenes: List<Scene>, val refuse: String? = null)

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val question = request.history.last().content
        val seconds = targetSeconds(question)
        emit(AgentEvent.Status("대본·장면 나누는 중 (약 ${seconds}초)"))
        val plan = plan(request.history, seconds)
        // 거절은 장면이 비어 있으므로 먼저 본다
        plan?.refuse?.let {
            emit(AgentEvent.Token("이 영상은 만들지 않습니다: $it\n실제 인물의 얼굴·목소리를 흉내 내는 영상은 만들지 않아요. 가상의 인물로 바꿔 요청해 주세요."))
            emit(AgentEvent.Done(type, generator.model, null))
            return@flow
        }
        if (plan == null || plan.scenes.isEmpty()) {
            emit(AgentEvent.Token("영상 대본을 만들지 못했습니다. 무엇을 담을지 조금 더 자세히 말해 주세요."))
            emit(AgentEvent.Done(type, generator.model, null))
            return@flow
        }
        val (imageWidth, imageHeight) = imageSize(plan.aspect)
        val (width, height) = videoSize(plan.aspect)
        reserve()
        val scenes = mutableListOf<VideoScene>()
        try {
            plan.scenes.forEachIndexed { i, scene ->
                emit(AgentEvent.Status("장면 ${i + 1}/${plan.scenes.size} 그리는 중"))
                val image = generator.generate(scene.image, imageWidth, imageHeight, steps)
                val voice = speech?.let { tts ->
                    runCatching { tts.synthesize(scene.narration, AudioFormat.M4A) }
                        .onFailure { if (it is CancellationException) throw it; log.warn { "내레이션 실패 (장면 ${i + 1}): ${it.message}" } }
                        .getOrNull()
                }
                scenes += VideoScene(image.png, voice, scene.narration, scene.motion)
            }
            emit(AgentEvent.Status("장면 합치는 중"))
            val mp4 = composer.compose(scenes, width, height, plan.title, runCatching { music() }.getOrNull())
            val asset = media.save(request.user.userId, MediaKind.VIDEO, "video/mp4", mp4, plan.title)
            emit(AgentEvent.Media(asset, mp4, plan.title))
            emit(AgentEvent.Token(summary(plan, width, height, mp4.size)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            emit(AgentEvent.Token(e.message ?: "영상을 만들지 못했습니다."))
        }
        emit(AgentEvent.Done(type, generator.model, null))
    }

    /** 답변에 대본을 남긴다: 다음 요청("3번 장면 더 밝게")에서 계획을 고쳐 쓸 수 있게 (G-05) */
    private fun summary(plan: Plan, width: Int, height: Int, bytes: Int) = buildString {
        append("영상을 만들었습니다: **${plan.title}** (${width}×${height}, 장면 ${plan.scenes.size}개, ${"%.1f".format(bytes / 1_048_576.0)}MB, AI 생성)\n\n")
        plan.scenes.forEachIndexed { i, s -> append("${i + 1}. ${s.narration}\n   그림: `${s.image}` · ${s.motion.name.lowercase()}\n") }
    }

    internal suspend fun plan(history: List<ChatMessage>, seconds: Int): Plan? {
        val question = history.last().content
        val recent = history.dropLast(1).takeLast(6).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(1_500)}" }
        val count = sceneCount(seconds)
        val prompt = """
            You plan a short narrated slideshow video. Read the user's latest request (usually Korean) and the recent conversation.
            - If the user asks to change a video made earlier ("3번 장면 더 밝게", "내레이션 짧게"), start from the previous plan in the conversation and apply the change.
            - Write exactly $count scenes for about $seconds seconds. Each scene: "narration" = 1-2 short natural Korean sentences (read aloud, about ${seconds / count} seconds),
              "image" = one English FLUX prompt (max 60 words: subject, style, composition, lighting) that matches the narration, keep a consistent style across scenes,
              "motion" = one of zoom_in, zoom_out, pan_left, pan_right (vary them).
            - People in images must be fictional. Do not put text in images.
            - "aspect": "portrait" for shorts/reels/세로/휴대폰, "landscape" for 가로/유튜브, otherwise "landscape".
            - "title": short Korean title.
            - If the request asks to imitate a real, identifiable person's face or voice (celebrity, politician, a named acquaintance, deepfake), set "refuse" to a short Korean reason and leave scenes empty.
            Reply with one JSON object only: {"title": "...", "aspect": "...", "refuse": null, "scenes": [{"narration": "...", "image": "...", "motion": "..."}]}

            Recent conversation:
            ${recent.ifBlank { "(none)" }}

            Latest request: $question
        """.trimIndent()
        return try {
            val spec = models.acquire(ModelRole.GENERAL)
            val text = inference.chat(
                ChatRequest(spec.name, listOf(ChatMessage(ChatRole.USER, prompt)), temperature = 0.4, maxTokens = 2_500, reasoningEffort = spec.reasoningEffort, keepAlive = spec.keepAlive),
            ).filterIsInstance<ChatChunk.Delta>().fold(StringBuilder()) { acc, chunk -> acc.append(chunk.text) }.toString()
            parse(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "영상 계획 실패" }
            null
        }
    }

    internal fun parse(text: String): Plan? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        fun JsonObject.str(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val refuse = obj.str("refuse")
        val scenes = runCatching { obj["scenes"]!!.jsonArray }.getOrNull().orEmpty().mapNotNull { element ->
            val scene = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val narration = scene.str("narration") ?: return@mapNotNull null
            val image = scene.str("image") ?: return@mapNotNull null
            val motion = scene.str("motion")?.uppercase()?.let { m -> Motion.entries.firstOrNull { it.name == m } } ?: Motion.ZOOM_IN
            Scene(narration.take(300), image.take(1_000), motion)
        }.take(MAX_SCENES)
        if (refuse == null && scenes.isEmpty()) return null
        return Plan(obj.str("title")?.take(80) ?: "Vesta 영상", obj.str("aspect") ?: "landscape", scenes, refuse)
    }

    companion object {
        const val MAX_SCENES = 12
        private val SECONDS = Regex("(\\d{1,3})\\s*초")
        private val MINUTES = Regex("(\\d{1,2})\\s*분")

        /** "30초", "1분" → 초. 없으면 30초. 10초~3분 */
        fun targetSeconds(text: String): Int {
            val s = SECONDS.find(text)?.groupValues?.get(1)?.toInt() ?: MINUTES.find(text)?.groupValues?.get(1)?.toInt()?.times(60) ?: 30
            return s.coerceIn(10, 180)
        }

        /** 장면 하나에 약 5초 (3~12개) */
        fun sceneCount(seconds: Int) = (seconds / 5).coerceIn(3, MAX_SCENES)

        /** FLUX는 16의 배수 크기 */
        fun imageSize(aspect: String) = when (aspect) {
            "portrait" -> 576 to 1024
            "square" -> 1024 to 1024
            else -> 1024 to 576
        }

        fun videoSize(aspect: String) = when (aspect) {
            "portrait" -> 720 to 1280
            "square" -> 1080 to 1080
            else -> 1280 to 720
        }
    }
}
