package dev.vesta.core.media

import dev.vesta.core.UserId

/** 질문에 붙인 이미지 (PROJECT.md V-04). [mime]은 image/png·image/jpeg·image/webp·image/gif */
class ImageInput(val mime: String, val bytes: ByteArray) {
    companion object {
        /** 파일 앞부분으로 형식을 확인한다 (확장자·Content-Type은 믿지 않는다). 이미지가 아니면 null */
        fun sniff(bytes: ByteArray): String? = when {
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            bytes.size > 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }

        /** 형식을 확인해 만든다. 이미지가 아니면 null */
        fun of(bytes: ByteArray): ImageInput? = sniff(bytes)?.let { ImageInput(it, bytes) }

        private fun ByteArray.startsWith(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}

/**
 * IMAGE: 질문에 붙인 이미지, GENERATED: Vesta가 만든 이미지 (갤러리, V-08), VIDEO: Vesta가 만든 영상 (M24, G-04),
 * SCENE: 영상 장면 재료(그림·내레이션, 고치기용, G-05). 갤러리에는 보이지 않는다
 */
enum class MediaKind { IMAGE, AUDIO, GENERATED, VIDEO, SCENE }

/** 저장한 미디어 (PROJECT.md 11장 MediaAsset). 파일은 `~/.vesta/media/<사용자>/`에 둔다. [caption]은 생성 프롬프트 등 */
data class MediaAsset(
    val id: String,
    val userId: UserId,
    val kind: MediaKind,
    val mime: String,
    val size: Long,
    val caption: String? = null,
    val createdAt: java.time.Instant? = null,
)

/** 미디어 저장소. 조회는 항상 사용자 범위로 제한한다 (U-03) */
interface MediaStore {
    suspend fun save(userId: UserId, kind: MediaKind, mime: String, bytes: ByteArray, caption: String? = null): MediaAsset
    /** 다른 사용자의 것이면 null */
    suspend fun load(userId: UserId, id: String): Pair<MediaAsset, ByteArray>?
    /** 최근 것부터 */
    suspend fun list(userId: UserId, kind: MediaKind, limit: Int): List<MediaAsset> = emptyList()
    /** 파일과 기록을 지운다 (영상이면 그 장면 그림·고치기 기록도). 본인 것이 아니거나 없으면 false */
    suspend fun delete(userId: UserId, id: String): Boolean = false
    /** 이 대화에 붙은 미디어(질문 이미지·만든 이미지·영상)를 모두 지운다. 지운 수 */
    suspend fun deleteSession(userId: UserId, sessionId: String): Int = 0
}

/** 생성한 이미지 (PNG, 메타데이터에 "AI 생성") */
class GeneratedImage(val png: ByteArray, val seed: Long)

/** 이미지 생성 (PROJECT.md 8장, V-06). 준비가 안 됐거나 실패하면 [MediaException] */
interface ImageGenerator {
    /** 이 모델 이름 (답변·메타데이터에 표시) */
    val model: String
    suspend fun generate(prompt: String, width: Int, height: Int, steps: Int, seed: Long? = null): GeneratedImage
}

/** 음성 인식 (PROJECT.md 8장, V-01). [audio]는 형식 상관없음 (ffmpeg로 바꾼다) */
interface SpeechToText {
    suspend fun transcribe(audio: ByteArray, language: String = "ko"): String
}

/** 음성 합성 (V-03). 돌려주는 형식은 [format] (m4a: 웹, ogg: Telegram 음성 메시지) */
/**
 * 이어 말하기 (PROJECT.md V-11, M19): 문장을 넣는 대로 합성해 소리(48kHz 16비트 스테레오 BE)를 흘린다.
 * 문장마다 파일을 만드는 [TextToSpeech]와 달리 첫 소리가 바로 나오고 문장 사이가 끊기지 않는다
 */
interface StreamingSpeech {
    fun open(onPcm: (ByteArray) -> Unit): SpeechStream
}

interface SpeechStream : AutoCloseable {
    /** 읽을 글 (마크다운이면 읽기 좋게 다듬는다) */
    fun say(text: String)
    /** 더 넣을 글이 없다. 남은 소리를 다 흘리면 [await]가 끝난다 */
    fun finish()
    suspend fun await()
    /** 바로 멈춘다 */
    override fun close()
}

interface TextToSpeech {
    suspend fun synthesize(text: String, format: AudioFormat): ByteArray
    /** [voice] 목소리로 (사용자 설정 U-07). 목소리를 고를 수 없는 구현은 기본 목소리 */
    suspend fun synthesize(text: String, format: AudioFormat, voice: String?): ByteArray = synthesize(text, format)
}

enum class AudioFormat(val mime: String) {
    M4A("audio/mp4"),
    OGG_OPUS("audio/ogg"),
    /** Discord 음성 채널로 보낼 날 소리: 48kHz 16비트 스테레오 big-endian (M19) */
    PCM_48K_STEREO("audio/L16;rate=48000;channels=2"),
}

/** 영상 장면의 카메라 움직임 (Ken Burns, G-02) */
enum class Motion { ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT }

/**
 * 영상 장면 하나 (G-02): 그림(PNG) 또는 움직이는 클립(mp4, G-03) + 내레이션(소리, 형식 상관없음) + 화면 아래 자막.
 * [speed]: 내레이션 빠르기 (0.7~1.5, G-05 "내레이션 빠르게")
 */
class VideoScene(
    val image: ByteArray,
    val narration: ByteArray?,
    val subtitle: String,
    val motion: Motion = Motion.ZOOM_IN,
    val speed: Double = 1.0,
    val clip: ByteArray? = null,
    /** [clip]의 길이 (초). 장면이 더 길면 최대 두 배까지 천천히 재생한다 */
    val clipSeconds: Double = 0.0,
)

/** 생성형 짧은 클립 (G-03): 장면 그림을 움직이는 몇 초짜리 영상(mp4)으로. 준비가 안 됐거나 실패하면 [MediaException] */
interface ClipGenerator {
    val model: String
    suspend fun animate(image: ByteArray, prompt: String, width: Int, height: Int, seconds: Double): ByteArray
}

/** 영상 프로젝트의 장면 (G-05): 계획 + 만든 재료(SCENE 자산 ID) */
@kotlinx.serialization.Serializable
data class ProjectScene(
    val narration: String,
    val image: String,
    val motion: Motion,
    val imageAsset: String,
    val voiceAsset: String? = null,
    /** 움직이는 클립으로 만든 장면 (G-03) */
    val clipAsset: String? = null,
    val animate: Boolean = false,
)

/** 만든 영상 하나의 계획과 재료 (G-05): 같은 대화에서 "3번 장면 더 밝게"처럼 바뀐 장면만 다시 만든다 */
@kotlinx.serialization.Serializable
data class VideoProject(
    val videoAsset: String,
    val title: String,
    val aspect: String,
    val speed: Double,
    val scenes: List<ProjectScene>,
)

interface VideoProjectRepository {
    suspend fun save(userId: UserId, sessionId: String, project: VideoProject)
    /** 이 대화에서 마지막으로 만든 영상 */
    suspend fun latest(userId: UserId, sessionId: String): VideoProject?
}

/** 장면을 이어 붙여 mp4를 만든다 (G-02). [onScene]은 장면 하나를 마칠 때마다 (진행률) */
interface VideoComposer {
    suspend fun compose(
        scenes: List<VideoScene>,
        width: Int,
        height: Int,
        title: String,
        music: ByteArray? = null,
        onScene: suspend (Int) -> Unit = {},
    ): ByteArray
}

/** 미디어 처리 중 사용자에게 보여 줄 실패 (짧은 한국어 문장) */
class MediaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 백그라운드 작업 상태 (M24) */
enum class JobStatus { QUEUED, RUNNING, DONE, FAILED }

/** 백그라운드 작업 하나의 지금 상태. [mediaId]는 만든 결과물(영상), [text]는 끝난 뒤 남길 답 */
data class JobState(
    val id: String,
    val userId: UserId,
    val sessionId: String,
    val title: String,
    val status: JobStatus,
    val progress: String? = null,
    val mediaId: String? = null,
    val mime: String? = null,
    val text: String? = null,
)

/** 작업이 만든 것: 결과물(없을 수 있음)과 답 */
class JobResult(val media: MediaAsset?, val bytes: ByteArray?, val text: String)

/**
 * 오래 걸리는 일을 백그라운드로 (M24 영상 만들기). 무거운 일이라 한 번에 하나씩 돌린다.
 * 끝나면 결과를 그 대화에 답 메시지로 남기고(결과물 첨부), 요청한 메신저로 알린다
 */
interface BackgroundJobs {
    fun submit(
        user: dev.vesta.core.UserContext,
        session: dev.vesta.core.session.Session,
        title: String,
        agent: dev.vesta.core.AgentType,
        work: suspend (progress: suspend (String) -> Unit) -> JobResult,
    ): JobState
    fun get(userId: UserId, id: String): JobState?
}
