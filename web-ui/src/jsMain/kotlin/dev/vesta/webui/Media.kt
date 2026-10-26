package dev.vesta.webui

import dev.vesta.api.ApiJson
import dev.vesta.api.CSRF_HEADER
import dev.vesta.api.SpeechRequestDto
import dev.vesta.api.TranscriptDto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.url.URL
import org.w3c.fetch.RequestInit
import org.w3c.files.Blob
import org.w3c.files.FileReader
import kotlin.js.json

/**
 * 음성·이미지 (PROJECT.md V-01·V-03·V-04).
 * 녹음은 브라우저 MediaRecorder(크롬 webm/opus, 사파리 mp4/aac). 마이크는 HTTPS(Tailscale)나 localhost에서만 열린다.
 */
class Recorder {
    private var recorder: dynamic = null
    private var stream: dynamic = null
    private val chunks = mutableListOf<dynamic>()
    private var done: CompletableDeferred<Blob>? = null
    val recording get() = recorder != null

    /** 마이크를 열고 녹음을 시작한다. 권한이 없으면 예외 */
    suspend fun start() {
        val media = window.navigator.asDynamic().mediaDevices ?: throw IllegalStateException("이 브라우저(또는 HTTP 주소)에서는 마이크를 쓸 수 없습니다")
        val opened: dynamic = (media.getUserMedia(json("audio" to true)) as kotlin.js.Promise<dynamic>).await()
        stream = opened
        val type = listOf("audio/webm;codecs=opus", "audio/mp4", "audio/webm").firstOrNull { js("MediaRecorder").isTypeSupported(it) as Boolean }
        val options = if (type != null) json("mimeType" to type) else json()
        // js() 안에서는 지역 변수만 쓴다 (속성 이름은 컴파일하면 바뀐다)
        val created: dynamic = js("new MediaRecorder(opened, options)")
        chunks.clear()
        val deferred = CompletableDeferred<Blob>()
        done = deferred
        created.ondataavailable = { event: dynamic -> if ((event.data.size as Number).toInt() > 0) chunks.add(event.data) }
        created.onstop = { _: dynamic ->
            val blobType = (created.mimeType as? String)?.ifEmpty { null } ?: "audio/webm"
            deferred.complete(Blob(chunks.toTypedArray(), js("({type: blobType})")))
        }
        created.start()
        recorder = created
    }

    /** 녹음을 끝내고 소리 파일을 돌려준다 */
    suspend fun stop(): Blob {
        val active = recorder ?: throw IllegalStateException("녹음 중이 아닙니다")
        active.stop()
        stream?.getTracks()?.forEach { track: dynamic -> track.stop() }
        recorder = null
        stream = null
        return done!!.await()
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        stream?.getTracks()?.forEach { track: dynamic -> track.stop() }
        recorder = null
        stream = null
    }
}

object MediaApi {
    /** 파일 그대로 올리기 (`PUT`, 본문은 파일, M36 F-04 문서 올리기) */
    suspend fun putFile(path: String, file: Blob) {
        val headers = json("Content-Type" to "application/octet-stream", "Accept" to "application/json")
        Http.csrf?.let { headers[CSRF_HEADER] = it }
        val response = window.fetch(path, RequestInit(method = "PUT", headers = headers, body = file, credentials = "same-origin".asDynamic())).await()
        if (!response.ok) throw HttpError(response.status.toInt(), errorOf(response.text().await()) ?: "올리기 실패 ${response.status}")
    }

    /** 녹음 → 글 (`POST /api/stt`). 인식한 글은 바로 보내지 않고 입력란에 넣는다 (V-02) */
    suspend fun transcribe(audio: Blob): String {
        val headers = json("Content-Type" to (audio.type.ifEmpty { "application/octet-stream" }), "Accept" to "application/json")
        Http.csrf?.let { headers[CSRF_HEADER] = it }
        val response = window.fetch("/api/stt", RequestInit(method = "POST", headers = headers, body = audio, credentials = "same-origin".asDynamic())).await()
        val text = response.text().await()
        if (!response.ok) throw HttpError(response.status.toInt(), errorOf(text) ?: "음성 인식 실패 ${response.status}")
        return ApiJson.decodeFromString(TranscriptDto.serializer(), text).text
    }

    /** 답변 읽어 주기 (`POST /api/speech`, m4a). 재생할 주소(blob:)를 돌려준다 */
    suspend fun speech(text: String): String {
        val headers = json("Content-Type" to "application/json")
        Http.csrf?.let { headers[CSRF_HEADER] = it }
        val body = ApiJson.encodeToString(SpeechRequestDto.serializer(), SpeechRequestDto(text))
        val response = window.fetch("/api/speech", RequestInit(method = "POST", headers = headers, body = body, credentials = "same-origin".asDynamic())).await()
        if (!response.ok) throw HttpError(response.status.toInt(), errorOf(response.text().await()) ?: "읽어 주기 실패 ${response.status}")
        return URL.createObjectURL(response.blob().await())
    }

    private fun errorOf(text: String) = runCatching { ApiJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
}

/** 질문에 붙일 이미지 (화면 미리보기용 data URL) */
class PendingImage(val dataUrl: String)

object Images {
    /** 휴대폰 사진(수 MB)은 긴 변 1600px JPEG로 줄인다. 작은 PNG·JPEG·WebP는 그대로 */
    private const val MAX_SIDE = 1600
    private const val KEEP_BYTES = 1_500_000

    suspend fun prepare(file: Blob): PendingImage {
        if (!file.type.startsWith("image/")) throw IllegalArgumentException("이미지 파일이 아닙니다")
        if (file.size.toDouble() <= KEEP_BYTES && file.type in setOf("image/png", "image/jpeg", "image/webp")) return PendingImage(dataUrl(file))
        val url = URL.createObjectURL(file)
        try {
            val image = document.createElement("img") as HTMLImageElement
            val loaded = CompletableDeferred<Unit>()
            image.onload = { loaded.complete(Unit) }
            image.onerror = { _, _, _, _, _ -> loaded.completeExceptionally(IllegalArgumentException("이미지를 열지 못했습니다")); null }
            image.src = url
            loaded.await()
            val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(image.naturalWidth, image.naturalHeight))
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            canvas.width = (image.naturalWidth * scale).toInt().coerceAtLeast(1)
            canvas.height = (image.naturalHeight * scale).toInt().coerceAtLeast(1)
            canvas.getContext("2d").asDynamic().drawImage(image, 0, 0, canvas.width, canvas.height)
            return PendingImage(canvas.toDataURL("image/jpeg", 0.85))
        } finally {
            URL.revokeObjectURL(url)
        }
    }

    private suspend fun dataUrl(file: Blob): String {
        val reader = FileReader()
        val result = CompletableDeferred<String>()
        reader.onload = { result.complete(reader.result.unsafeCast<String>()) }
        reader.onerror = { result.completeExceptionally(IllegalArgumentException("파일을 읽지 못했습니다")) }
        reader.readAsDataURL(file)
        return result.await()
    }
}
