package dev.vesta.media

import dev.vesta.core.media.GeneratedImage
import dev.vesta.core.media.ImageGenerator
import dev.vesta.core.media.MediaException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * 미디어 워커(mflux FLUX.1-schnell 4bit)로 이미지를 만든다 (PROJECT.md V-06).
 * 모델이 아직 없으면 워커가 공식 가중치를 받아 양자화하기 시작하고, 그동안은 "준비 중"으로 알린다.
 */
class WorkerImageGenerator(
    private val baseUrl: String,
    private val http: HttpClient,
    override val model: String = "FLUX.1-schnell",
    private val tokenFile: Path = WorkerSpeechToText.DEFAULT_TOKEN_FILE,
    private val timeoutMillis: Long = 600_000,
) : ImageGenerator {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(prompt: String, width: Int, height: Int, steps: Int, seed: Long?): GeneratedImage {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("width", width)
            put("height", height)
            put("steps", steps)
            seed?.let { put("seed", it) }
        }
        val response = try {
            http.post("$baseUrl/image") {
                header(WorkerSpeechToText.TOKEN_HEADER, runCatching { Files.readString(tokenFile).trim() }.getOrDefault(""))
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                // 모델을 처음 올릴 때는 오래 걸린다
                timeout { requestTimeoutMillis = timeoutMillis; socketTimeoutMillis = timeoutMillis }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MediaException("이미지 생성기(미디어 워커)에 연결하지 못했습니다. scripts/install-vesta.sh 로 설치했는지 확인하세요", e)
        }
        if (response.status.isSuccess()) {
            val png = response.bodyAsBytes()
            return GeneratedImage(png, response.headers["X-Seed"]?.toLongOrNull() ?: seed ?: 0)
        }
        val error = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val code = error?.get("error")?.jsonPrimitive?.contentOrNull
        val detail = error?.get("detail")?.jsonPrimitive?.contentOrNull
        if (response.status == HttpStatusCode.ServiceUnavailable && code == "preparing") throw MediaException(preparingMessage(detail))
        throw MediaException("이미지 생성 실패: ${code ?: response.status.value}")
    }

    companion object {
        /** 받기 실패 원인 중 사용자가 할 일이 있는 것(Hugging Face 로그인)을 알려 준다 */
        fun preparingMessage(detail: String?): String = when {
            detail == null -> "이미지 모델을 처음 준비하고 있습니다 (공식 FLUX.1-schnell 약 33GB를 받아 4bit로 줄여 저장, 한 번만). 끝나면 다시 요청하세요."
            "401" in detail || "403" in detail || "gated" in detail.lowercase() || "token" in detail.lowercase() ->
                "이미지 모델을 받지 못했습니다: Hugging Face 로그인과 FLUX.1-schnell 약관 동의가 필요합니다 (media-worker에서 uv run hf auth login). 로그인 후 다시 요청하세요."
            else -> "이미지 모델 준비 실패: ${detail.take(200)}. 다시 요청하면 다시 시도합니다."
        }
    }
}
