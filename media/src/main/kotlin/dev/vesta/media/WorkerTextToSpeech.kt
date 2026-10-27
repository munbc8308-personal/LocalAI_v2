package dev.vesta.media

import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.TextToSpeech
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 미디어 워커의 Qwen3-TTS(mlx-audio)로 읽어 준다 (M37 RV-04, `models.tts` engine: mlx-audio). 워커는 WAV를 돌려주고 ffmpeg로 바꾼다.
 * [speaker]는 Qwen3-TTS 화자(기본 sohee, 한국어). 사용자 목소리 설정이 화자 이름이 아니면(`say` 음성 등) 기본 화자
 */
class WorkerTextToSpeech(
    private val baseUrl: String,
    private val http: HttpClient,
    private val ffmpeg: Ffmpeg,
    private val speaker: String,
    private val maxChars: Int,
    private val tokenFile: java.nio.file.Path = WorkerSpeechToText.DEFAULT_TOKEN_FILE,
) : TextToSpeech {
    private fun token(): String = runCatching { java.nio.file.Files.readString(tokenFile).trim() }.getOrDefault("")

    override suspend fun synthesize(text: String, format: AudioFormat): ByteArray = synthesize(text, format, null)

    override suspend fun synthesize(text: String, format: AudioFormat, voice: String?): ByteArray {
        val chosen = voice?.lowercase()?.takeIf { it in SPEAKERS } ?: speaker
        val response = try {
            http.post("$baseUrl/tts") {
                header(WorkerSpeechToText.TOKEN_HEADER, token())
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("text", text.take(maxChars)); put("speaker", chosen); put("language", "Korean") }.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MediaException("읽어 주기(미디어 워커)에 연결하지 못했습니다. scripts/install-vesta.sh 로 설치했는지 확인하세요", e)
        }
        if (!response.status.isSuccess()) throw MediaException("읽어 주기 실패: ${response.bodyAsText().take(200)}")
        return ffmpeg.encode(response.readRawBytes(), format)
    }

    companion object {
        /** Qwen3-TTS CustomVoice 화자 */
        val SPEAKERS = setOf("serena", "vivian", "uncle_fu", "ryan", "aiden", "ono_anna", "sohee", "eric", "dylan")
    }
}
