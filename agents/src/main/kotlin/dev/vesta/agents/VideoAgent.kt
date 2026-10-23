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
import dev.vesta.core.media.ClipGenerator
import dev.vesta.core.media.ProjectScene
import dev.vesta.core.media.VideoProject
import dev.vesta.core.media.VideoProjectRepository
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
    /** 만든 영상의 계획·재료 (G-05 고치기). 없으면 매번 새로 */
    private val projects: VideoProjectRepository? = null,
    /** 움직이는 클립 (G-03). 없으면 모든 장면을 그림 + 확대·이동으로 */
    private val clips: ClipGenerator? = null,
    /** 영상 하나에서 움직이는 클립으로 만들 장면 수 상한 (클립은 장면 하나에 수십 초~수 분) */
    private val maxClips: Int = 2,
    /** 클립 모델 자리 비우기 */
    private val reserveClips: suspend () -> Unit = {},
) : Agent {
    override val type = AgentType.VIDEO
    private val json = Json { ignoreUnknownKeys = true }

    internal data class Scene(val narration: String, val image: String, val motion: Motion, val animate: Boolean = false)
    internal data class Plan(val title: String, val aspect: String, val scenes: List<Scene>, val refuse: String? = null, val speed: Double = 1.0, val edit: Boolean = false)

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val question = request.history.last().content
        val seconds = targetSeconds(question)
        val previous = projects?.latest(request.user.userId, request.session.id.value)
        emit(AgentEvent.Status(if (previous != null) "대본·장면 나누는 중 (앞 영상 \"${previous.title}\"을 고칠지 함께 봄)" else "대본·장면 나누는 중 (약 ${seconds}초)"))
        val plan = plan(request.history, seconds, previous)
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
        // 고치기면 비율은 앞 영상 그대로 (그림을 다시 쓰려면 크기가 같아야 한다)
        val base = previous?.takeIf { plan.edit }
        val aspect = base?.aspect ?: plan.aspect
        val (imageWidth, imageHeight) = imageSize(aspect)
        val (width, height) = videoSize(aspect)
        val user = request.user.userId
        val scenes = mutableListOf<VideoScene>()
        val saved = mutableListOf<ProjectScene>()
        var drawn = 0
        var spoken = 0
        var animated = 0
        try {
            val animateIndexes = if (clips == null) emptySet() else plan.scenes.withIndex().filter { it.value.animate }.take(maxClips).map { it.index }.toSet()
            plan.scenes.forEachIndexed { i, scene ->
                // 앞 영상의 같은 그림·내레이션·클립은 다시 쓴다 (바뀐 장면만 다시 만든다)
                val oldImage = base?.scenes?.firstOrNull { it.image == scene.image }
                val reused = oldImage?.let { media.load(user, it.imageAsset)?.second }
                val image = reused ?: run {
                    if (drawn == 0) reserve()
                    drawn++
                    emit(AgentEvent.Status("장면 ${i + 1}/${plan.scenes.size} 그리는 중"))
                    generator.generate(scene.image, imageWidth, imageHeight, steps).png
                }
                val imageAsset = if (reused != null && oldImage != null) oldImage.imageAsset else media.save(user, MediaKind.SCENE, "image/png", image, scene.image).id
                val oldVoice = base?.scenes?.firstOrNull { it.narration == scene.narration && it.voiceAsset != null }
                val voice = oldVoice?.voiceAsset?.let { media.load(user, it)?.second } ?: speech?.let { tts ->
                    spoken++
                    runCatching { tts.synthesize(scene.narration, AudioFormat.M4A) }
                        .onFailure { if (it is CancellationException) throw it; log.warn { "내레이션 실패 (장면 ${i + 1}): ${it.message}" } }
                        .getOrNull()
                }
                val voiceAsset = oldVoice?.voiceAsset ?: voice?.let { media.save(user, MediaKind.SCENE, "audio/mp4", it, scene.narration).id }
                val oldClip = base?.scenes?.firstOrNull { it.image == scene.image && it.clipAsset != null }?.takeIf { i in animateIndexes }
                var clipBytes = oldClip?.clipAsset?.let { media.load(user, it)?.second }
                if (clipBytes == null && i in animateIndexes && clips != null) {
                    if (animated == 0) reserveClips()
                    animated++
                    emit(AgentEvent.Status("장면 ${i + 1}/${plan.scenes.size} 움직이는 클립 만드는 중 (${clips.model})"))
                    clipBytes = runCatching { clips.animate(image, scene.image, imageWidth, imageHeight, CLIP_SECONDS) }
                        .onFailure { if (it is CancellationException) throw it; log.warn { "클립 실패 (장면 ${i + 1}), 그림으로: ${it.message}" } }
                        .getOrNull()
                }
                val clipAsset = oldClip?.clipAsset ?: clipBytes?.let { media.save(user, MediaKind.SCENE, "video/mp4", it, scene.image).id }
                scenes += VideoScene(image, voice, scene.narration, scene.motion, plan.speed, clipBytes)
                saved += ProjectScene(scene.narration, scene.image, scene.motion, imageAsset, voiceAsset, clipAsset, clipBytes != null)
            }
            emit(AgentEvent.Status(if (base != null) "장면 합치는 중 (그림 ${drawn}개·내레이션 ${spoken}개만 새로)" else "장면 합치는 중"))
            val mp4 = composer.compose(scenes, width, height, plan.title, runCatching { music() }.getOrNull())
            val asset = media.save(user, MediaKind.VIDEO, "video/mp4", mp4, plan.title)
            projects?.save(user, request.session.id.value, VideoProject(asset.id, plan.title, aspect, plan.speed, saved))
            emit(AgentEvent.Media(asset, mp4, plan.title))
            emit(AgentEvent.Token(summary(plan.copy(aspect = aspect), saved, width, height, mp4.size, base?.let { drawn to spoken })))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            emit(AgentEvent.Token(e.message ?: "영상을 만들지 못했습니다."))
        }
        emit(AgentEvent.Done(type, generator.model, null))
    }

    /** 답변에 대본을 남긴다 (사람이 읽고 "3번 장면 더 밝게"처럼 고치라고 할 수 있게, G-05) */
    private fun summary(plan: Plan, scenes: List<ProjectScene>, width: Int, height: Int, bytes: Int, redone: Pair<Int, Int>?) = buildString {
        append(if (redone != null) "영상을 고쳤습니다" else "영상을 만들었습니다")
        append(": **${plan.title}** (${width}×${height}, 장면 ${plan.scenes.size}개, ${"%.1f".format(bytes / 1_048_576.0)}MB, AI 생성)")
        if (plan.speed != 1.0) append(" · 내레이션 ${"%.2f".format(plan.speed)}배")
        append("\n")
        redone?.let { (images, voices) -> append("새로 만든 것: 그림 ${images}개, 내레이션 ${voices}개 (나머지는 앞 영상 그대로)\n") }
        append("\n")
        scenes.forEachIndexed { i, s ->
            append("${i + 1}. ${s.narration}\n   그림: `${s.image}` · ${s.motion.name.lowercase()}${if (s.animate) " · 움직이는 클립" else ""}\n")
        }
        append("\n장면 단위로 고칠 수 있어요 (예: \"2번 장면 더 밝게\", \"내레이션 빠르게\").")
    }

    internal suspend fun plan(history: List<ChatMessage>, seconds: Int, previous: VideoProject? = null): Plan? {
        val question = history.last().content
        val recent = history.dropLast(1).takeLast(6).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(1_500)}" }
        val count = sceneCount(seconds)
        val earlier = previous?.let { p ->
            buildString {
                append("Previous video in this conversation (title \"${p.title}\", aspect ${p.aspect}, speed ${p.speed}):\n")
                p.scenes.forEachIndexed { i, s -> append("${i + 1}. narration: ${s.narration} | image: ${s.image} | motion: ${s.motion.name.lowercase()} | animate: ${s.animate}\n") }
            }
        }
        val editRule = if (earlier != null) """
            - If the latest request changes the previous video ("3번 장면 더 밝게", "내레이션 빠르게", "마지막 장면 바꿔", "고양이로 바꿔"), set "mode": "edit" and return the FULL plan:
              copy every unchanged scene EXACTLY (same narration and image text, so it is reused), and change only what was asked. Keep the aspect.
              "내레이션 빠르게/느리게" changes "speed" (1.0 normal, 1.2 faster, 0.85 slower). Otherwise "mode": "new".
        """.trimIndent() else ""
        val prompt = """
            You plan a short narrated slideshow video. Read the user's latest request (usually Korean) and the recent conversation.
            $editRule
            - For a new video, write exactly $count scenes for about $seconds seconds. Each scene: "narration" = 1-2 short natural Korean sentences (read aloud, about ${seconds / count} seconds),
              "image" = one English FLUX prompt (max 60 words: subject, style, composition, lighting) that matches the narration, keep a consistent style across scenes,
              "motion" = one of zoom_in, zoom_out, pan_left, pan_right (vary them),
              "animate" = true only for at most two scenes where real movement matters (flowing water, walking people, moving vehicles), otherwise false.
            - People in images must be fictional. Do not put text in images.
            - "aspect": "portrait" for shorts/reels/세로/휴대폰, "landscape" for 가로/유튜브, otherwise "landscape".
            - "title": short Korean title.
            - If the request asks to imitate a real, identifiable person's face or voice (celebrity, politician, a named acquaintance, deepfake), set "refuse" to a short Korean reason and leave scenes empty.
            Reply with one JSON object only: {"mode": "new", "title": "...", "aspect": "...", "speed": 1.0, "refuse": null, "scenes": [{"narration": "...", "image": "...", "motion": "...", "animate": false}]}

            ${earlier.orEmpty()}
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
            Scene(narration.take(300), image.take(1_000), motion, scene.str("animate") == "true")
        }.take(MAX_SCENES)
        if (refuse == null && scenes.isEmpty()) return null
        val speed = obj.str("speed")?.toDoubleOrNull()?.coerceIn(0.7, 1.5) ?: 1.0
        return Plan(obj.str("title")?.take(80) ?: "Vesta 영상", obj.str("aspect") ?: "landscape", scenes, refuse, speed, obj.str("mode") == "edit")
    }

    companion object {
        const val MAX_SCENES = 12
        /** 움직이는 클립 길이 (내레이션이 더 길면 마지막 프레임을 늘린다) */
        const val CLIP_SECONDS = 4.0
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
