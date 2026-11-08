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
import dev.vesta.core.media.BackgroundJobs
import dev.vesta.core.media.JobResult
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
    /** 있으면 만들기를 백그라운드로 넘기고 답을 바로 마친다 (끝나면 같은 대화에 결과를 남기고 알린다) */
    private val jobs: BackgroundJobs? = null,
) : Agent {
    override val type = AgentType.VIDEO
    private val json = Json { ignoreUnknownKeys = true }

    /** [photo]: 붙인 사진 번호 (0부터, G-07). 있으면 그리지 않고 그 사진을 쓴다 */
    internal data class Scene(val narration: String, val image: String, val motion: Motion, val animate: Boolean = false, val photo: Int? = null)
    internal data class Plan(val title: String, val aspect: String, val scenes: List<Scene>, val refuse: String? = null, val speed: Double = 1.0, val edit: Boolean = false)

    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        val question = request.history.last().content
        val seconds = targetSeconds(question)
        // 붙인 사진 (G-07): 순서대로 한 장이 한 장면. 사진을 붙였으면 늘 새 영상
        val photos = request.history.last().images.take(MAX_SCENES).map { it.bytes }
        if (photos.any { ImageFit.oversized(it) }) {
            emit(AgentEvent.Token(ImageAgent.TOO_LARGE.replace("그림이", "사진 중에")))
            emit(AgentEvent.Done(type, generator.model, null))
            return@flow
        }
        val previous = if (photos.isEmpty()) projects?.latest(request.user.userId, request.session.id.value) else null
        emit(AgentEvent.Status(when {
            photos.isNotEmpty() -> "사진 ${photos.size}장을 보고 대본 쓰는 중"
            previous != null -> "대본·장면 나누는 중 (앞 영상 \"${previous.title}\"을 고칠지 함께 봄)"
            else -> "대본·장면 나누는 중 (약 ${seconds}초)"
        }))
        val plan = plan(request.history, seconds, previous, photos)
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
        if (jobs != null) {
            // 몇 분 걸리므로 백그라운드로: 답은 바로 마치고, 끝나면 같은 대화에 결과를 남기고 알린다
            val job = jobs.submit(request.user, request.session, plan.title, type) { progress -> produce(request, plan, previous, photos, progress) }
            emit(AgentEvent.JobStarted(job.id, plan.title))
            emit(AgentEvent.Token(started(plan, previous, job.progress)))
            emit(AgentEvent.Done(type, generator.model, null))
            return@flow
        }
        try {
            val result = produce(request, plan, previous, photos) { emit(AgentEvent.Status(it)) }
            val asset = result.media
            val bytes = result.bytes
            if (asset != null && bytes != null) emit(AgentEvent.Media(asset, bytes, plan.title))
            emit(AgentEvent.Token(result.text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            emit(AgentEvent.Token(e.message ?: "영상을 만들지 못했습니다."))
        }
        emit(AgentEvent.Done(type, generator.model, null))
    }

    /** 백그라운드로 넘길 때의 답: 무엇을 얼마나 만드는지와 기다리는 동안 할 수 있는 것 */
    private fun started(plan: Plan, previous: VideoProject?, queue: String?) = buildString {
        val clipsPlanned = if (clips == null) 0 else plan.scenes.count { it.animate }.coerceAtMost(maxClips)
        val drawing = plan.scenes.count { it.photo == null }
        val minutes = (drawing * 10 + (plan.scenes.size - drawing) * 3 + clipsPlanned * 110 + 30) / 60.0
        append(if (previous != null && plan.edit) "영상을 고치기 시작했습니다" else "영상을 만들기 시작했습니다")
        append(": **${plan.title}** (장면 ${plan.scenes.size}개")
        if (drawing < plan.scenes.size) append(", 붙인 사진 ${plan.scenes.size - drawing}장")
        if (clipsPlanned > 0) append(", 움직이는 장면 ${clipsPlanned}개")
        append(", 약 ${"%.0f".format(maxOf(1.0, minutes))}분)\n")
        queue?.let { append("$it.\n") }
        append("끝나면 이 대화에 영상을 올리고 알려 드릴게요. 그동안 다른 것을 물어보셔도 됩니다.")
    }

    /**
     * 장면마다 그림·내레이션(·클립)을 만들고 합쳐 갤러리에 남긴다. 고치기면 앞 영상의 같은 재료를 다시 쓴다.
     * 실패하면 [MediaException]
     */
    private suspend fun produce(request: AgentRequest, plan: Plan, previous: VideoProject?, photos: List<ByteArray>, progress: suspend (String) -> Unit): JobResult {
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
        val animateIndexes = if (clips == null) emptySet() else plan.scenes.withIndex().filter { it.value.animate }.take(maxClips).map { it.index }.toSet()
        plan.scenes.forEachIndexed { i, scene ->
            // 앞 영상의 같은 그림·내레이션·클립은 다시 쓴다 (바뀐 장면만 다시 만든다)
            val oldImage = base?.scenes?.firstOrNull { it.image == scene.image }
            val reused = oldImage?.let { media.load(user, it.imageAsset)?.second }
            // 붙인 사진 (G-07): 장면 크기에 맞춰 가운데를 자른다. 못 읽는 형식(WebP 등)이면 그대로 (ffmpeg가 맞춘다)
            val photo = scene.photo?.let { n -> photos.getOrNull(n) }?.let { ImageFit.cover(it, imageWidth, imageHeight) ?: it }
            val image = reused ?: photo ?: run {
                if (drawn == 0) reserve()
                drawn++
                progress("장면 ${i + 1}/${plan.scenes.size} 그리는 중")
                generator.generate(scene.image, imageWidth, imageHeight, steps).png
            }
            val imageAsset = if (reused != null && oldImage != null) oldImage.imageAsset else media.save(user, MediaKind.SCENE, "image/png", image, scene.image).id
            val oldVoice = base?.scenes?.firstOrNull { it.narration == scene.narration && it.voiceAsset != null }
            val voice = oldVoice?.voiceAsset?.let { media.load(user, it)?.second } ?: speech?.takeIf { scene.narration.isNotBlank() }?.let { tts ->
                spoken++
                runCatching { tts.synthesize(scene.narration, AudioFormat.M4A) }
                    .onFailure { if (it is CancellationException) throw it; log.warn { "내레이션 실패 (장면 ${i + 1}): ${it.message}" } }
                    .getOrNull()
            }
            val voiceAsset = oldVoice?.voiceAsset ?: voice?.let { media.save(user, MediaKind.SCENE, "audio/mp4", it, scene.narration).id }
            val oldClip = base?.scenes?.firstOrNull { it.image == scene.image && it.clipAsset != null }?.takeIf { i in animateIndexes }
            var clipBytes = oldClip?.clipAsset?.let { media.load(user, it)?.second }
            val clipper = clips
            if (clipBytes == null && i in animateIndexes && clipper != null) {
                if (animated == 0) reserveClips()
                animated++
                progress("장면 ${i + 1}/${plan.scenes.size} 움직이는 클립 만드는 중 (${clipper.model}, 약 2분)")
                clipBytes = runCatching { clipper.animate(image, scene.image, imageWidth, imageHeight, CLIP_SECONDS) }
                    .onFailure { if (it is CancellationException) throw it; log.warn { "클립 실패 (장면 ${i + 1}), 그림으로: ${it.message}" } }
                    .getOrNull()
            }
            val clipAsset = oldClip?.clipAsset ?: clipBytes?.let { media.save(user, MediaKind.SCENE, "video/mp4", it, scene.image).id }
            scenes += VideoScene(image, voice, scene.narration, scene.motion, plan.speed, clipBytes, if (clipBytes != null) CLIP_SECONDS else 0.0)
            saved += ProjectScene(scene.narration, scene.image, scene.motion, imageAsset, voiceAsset, clipAsset, clipBytes != null)
        }
        progress(if (base != null) "장면 합치는 중 (그림 ${drawn}개·내레이션 ${spoken}개만 새로)" else "장면 합치는 중")
        val mp4 = composer.compose(scenes, width, height, plan.title, runCatching { music() }.getOrNull())
        val asset = media.save(user, MediaKind.VIDEO, "video/mp4", mp4, plan.title)
        projects?.save(user, request.session.id.value, VideoProject(asset.id, plan.title, aspect, plan.speed, saved))
        return JobResult(asset, mp4, summary(plan.copy(aspect = aspect), saved, width, height, mp4.size, base?.let { drawn to spoken }))
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
            val photo = plan.scenes.getOrNull(i)?.photo?.let { "붙인 사진 ${it + 1} · " }.orEmpty()
            append("${i + 1}. ${s.narration}\n   ${photo}그림: `${s.image}` · ${s.motion.name.lowercase()}${if (s.animate) " · 움직이는 클립" else ""}\n")
        }
        append("\n장면 단위로 고칠 수 있어요 (예: \"2번 장면 더 밝게\", \"내레이션 빠르게\").")
    }

    /** [photos]가 있으면 사진마다 한 장면 (G-07): 메인 모델이 사진을 보고 순서대로 내레이션을 쓴다 */
    internal suspend fun plan(history: List<ChatMessage>, seconds: Int, previous: VideoProject? = null, photos: List<ByteArray> = emptyList()): Plan? {
        val question = history.last().content
        val recent = history.dropLast(1).takeLast(6).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(1_500)}" }
        val count = if (photos.isNotEmpty()) photos.size else sceneCount(seconds)
        val shape = photos.mapNotNull { ImageFit.aspect(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val photoRule = if (photos.isNotEmpty()) """
            - The user attached ${photos.size} photo(s), shown to you in order. Make exactly ${photos.size} scenes, scene N uses photo N (same order). Do NOT invent other scenes.
              "narration" = 1-2 short natural Korean sentences about what that photo shows, connected into one story in the user's requested tone,
              "image" = a short English description of that photo (used as the motion prompt), "motion" as below,
              "animate" = true only if real movement would help AND the photo shows no real person's face; otherwise false.
            - "aspect": "${shape ?: "landscape"}" (most photos' shape) unless the user asks for another shape.
            - If the request asks to make a real person in the photos say or do things they did not (deepfake, fake statements), set "refuse".
        """.trimIndent() else ""
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
            $photoRule
            - For a new video without photos, write exactly $count scenes for about $seconds seconds. Each scene: "narration" = 1-2 short natural Korean sentences (read aloud, about ${seconds / count} seconds),
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
                // 붙인 사진은 메인 모델(비전)이 보고 대본을 쓴다
                ChatRequest(spec.name, listOf(ChatMessage(ChatRole.USER, prompt, images = if (photos.isNotEmpty()) history.last().images.take(photos.size) else emptyList())), temperature = 0.4, maxTokens = 2_500, reasoningEffort = spec.reasoningEffort, keepAlive = spec.keepAlive),
            ).filterIsInstance<ChatChunk.Delta>().fold(StringBuilder()) { acc, chunk -> acc.append(chunk.text) }.toString()
            parse(text)?.let { if (photos.isEmpty() || it.refuse != null) it else withPhotos(it, photos.size) }
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

    /** 사진 수만큼 장면을 맞추고 장면 N에 사진 N을 붙인다. 모델이 덜 썼으면 내레이션 없는 장면으로 채운다 (사진을 빼먹지 않게) */
    internal fun withPhotos(plan: Plan, count: Int): Plan {
        val motions = Motion.entries
        val scenes = (0 until count).map { i ->
            (plan.scenes.getOrNull(i) ?: Scene("", "photo ${i + 1}", motions[i % motions.size])).copy(photo = i)
        }
        return plan.copy(scenes = scenes, edit = false)
    }

    companion object {
        const val MAX_SCENES = 12
        /** 움직이는 클립 길이: 2초(49프레임). 장면이 더 길면 합칠 때 두 배까지 천천히, 그래도 남으면 마지막 프레임 (13.32 측정) */
        const val CLIP_SECONDS = 2.0
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
