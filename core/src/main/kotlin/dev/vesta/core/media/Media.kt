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

/** IMAGE: 질문에 붙인 이미지, GENERATED: Vesta가 만든 이미지 (갤러리, V-08) */
enum class MediaKind { IMAGE, AUDIO, GENERATED }

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

/** 미디어 처리 중 사용자에게 보여 줄 실패 (짧은 한국어 문장) */
class MediaException(message: String, cause: Throwable? = null) : Exception(message, cause)
