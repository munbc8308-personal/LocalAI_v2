package dev.vesta.media

import dev.vesta.core.media.MediaException
import dev.vesta.core.media.SpeechToText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
 */
class WorkerSpeechToText(
    private val baseUrl: String,
    private val http: HttpClient,
    private val ffmpeg: Ffmpeg,
    private val maxSeconds: Int,
) : SpeechToText {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Result(val text: String = "", val error: String? = null)

    @Serializable
    data class Health(val ok: Boolean = false, val stt: Stt? = null) {
        @Serializable
        data class Stt(val model: String = "", val loaded: Boolean = false, val bytes: Long = 0)
    }

    override suspend fun transcribe(audio: ByteArray, language: String): String {
        val wav = ffmpeg.toWav16k(audio, maxSeconds)
        val response = try {
            http.post("$baseUrl/stt") {
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

    /** 워커 상태. 연결 안 되면 null */
    suspend fun health(): Health? = runCatching {
        json.decodeFromString(Health.serializer(), http.get("$baseUrl/health").bodyAsText())
    }.getOrNull()
}
