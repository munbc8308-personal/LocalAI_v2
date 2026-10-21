package dev.vesta.media

import dev.vesta.core.media.MediaException
import dev.vesta.core.media.SpeechToText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * 미디어 워커(media-worker, mlx-whisper)로 음성을 인식한다 (PROJECT.md V-01).
 * 먼저 ffmpeg로 16kHz WAV로 바꿔 보낸다 (워커는 WAV만 받는다).
 * 워커는 토큰을 요구한다: 워커가 만든 [tokenFile](`~/.vesta/media-token`)을 읽어 `X-Vesta-Token`으로 보낸다.
 */
class WorkerSpeechToText(
    private val baseUrl: String,
    private val http: HttpClient,
    private val ffmpeg: Ffmpeg,
    private val maxSeconds: Int,
    private val tokenFile: java.nio.file.Path = DEFAULT_TOKEN_FILE,
) : SpeechToText {
    /** 워커가 처음 뜰 때 만들므로 필요할 때마다 읽는다 */
    private fun token(): String = runCatching { java.nio.file.Files.readString(tokenFile).trim() }.getOrDefault("")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Result(val text: String = "", val error: String? = null)

    /** [bytes]는 워커가 GPU에 올린 메모리 전체 (음성·이미지 모델) */
    @Serializable
    data class Health(val ok: Boolean = false, val bytes: Long = 0, val stt: Stt? = null, val image: Image? = null) {
        @Serializable
        data class Stt(val model: String = "", val loaded: Boolean = false, val bytes: Long = 0)

        @Serializable
        data class Image(
            val model: String = "",
            /** 양자화한 모델이 저장돼 있는지 */
            val ready: Boolean = false,
            val loaded: Boolean = false,
            /** 처음 받기·양자화 중 */
            val preparing: Boolean = false,
            val error: String? = null,
        )
    }

    override suspend fun transcribe(audio: ByteArray, language: String): String {
        val wav = ffmpeg.toWav16k(audio, maxSeconds)
        val response = try {
            http.post("$baseUrl/stt") {
                header(TOKEN_HEADER, token())
                parameter("language", language)
                contentType(ContentType("audio", "wav"))
                setBody(wav)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MediaException("음성 인식기(미디어 워커)에 연결하지 못했습니다. scripts/install-vesta.sh 로 설치했는지 확인하세요", e)
        }
        val result = runCatching { json.decodeFromString(Result.serializer(), response.bodyAsText()) }.getOrNull()
        if (!response.status.isSuccess() || result == null) throw MediaException("음성 인식 실패: ${result?.error ?: response.status.value}")
        return result.text.trim()
    }

    /** 워커가 올린 모델(음성·이미지)을 모두 내린다 (학습 전 메모리 확보) */
    suspend fun unloadAll(): Boolean = runCatching {
        http.post("$baseUrl/unload") { header(TOKEN_HEADER, token()) }.status.isSuccess()
    }.getOrDefault(false)

    /** 워커 상태. 연결 안 되면 null */
    suspend fun health(): Health? = runCatching {
        json.decodeFromString(Health.serializer(), http.get("$baseUrl/health") { header(TOKEN_HEADER, token()) }.bodyAsText())
    }.getOrNull()

    companion object {
        const val TOKEN_HEADER = "X-Vesta-Token"
        /** VESTA_HOME과 상관없이 하나 (워커는 이 Mac에 하나) */
        val DEFAULT_TOKEN_FILE: java.nio.file.Path = java.nio.file.Path.of(System.getProperty("user.home"), ".vesta", "media-token")
    }
}
