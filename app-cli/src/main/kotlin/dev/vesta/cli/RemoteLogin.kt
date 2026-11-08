package dev.vesta.cli

import dev.vesta.api.ApiJson
import dev.vesta.api.CSRF_HEADER
import dev.vesta.api.LoginDto
import dev.vesta.api.MeDto
import dev.vesta.core.config.VestaPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * 다른 PC의 CLI 로그인 (RC-01~RC-04). 웹과 같은 이름·비밀번호로 로그인해 받은 세션 쿠키와 CSRF 값을 `~/.vesta/remote.json`(권한 600)에 둔다.
 * 서버는 웹 UI와 똑같이 그 사용자로 본다: 손님·멤버 제한과 하루 질문 한도가 그대로이고, Mac 주인의 API 토큰은 밖에 나가지 않는다.
 * 주소는 https만 받는다 (비밀번호·쿠키가 오가므로). 예외: 이 기기 자신(127.0.0.1·localhost)과 Tailscale(`*.ts.net`, 이미 암호화)
 */
@Serializable
data class RemoteLogin(val url: String, val name: String, val session: String, val csrf: String) {
    /** 세션 쿠키와 CSRF 헤더 (서버는 GET 외 요청에서 CSRF를 본다) */
    fun applyTo(request: HttpRequestBuilder) {
        request.header(HttpHeaders.Cookie, "$COOKIE=$session")
        request.header(CSRF_HEADER, csrf)
    }

    /** 로그인한 사용자. 로그인이 끝났거나 서버에 닿지 않으면 예외 (글은 사용자에게 그대로 보여 준다) */
    suspend fun me(): MeDto = client(timeoutMillis = 10_000).use { http ->
        val response = runCatching { http.get("$url/api/me") { applyTo(this) } }
            .getOrElse { throw IllegalStateException("서버에 닿지 않습니다: $url (${it.message ?: it::class.simpleName})") }
        when {
            response.status.isSuccess() -> ApiJson.decodeFromString(MeDto.serializer(), response.bodyAsText())
            response.status.value == 401 -> throw IllegalStateException("로그인이 끝났습니다. vesta login $url 로 다시 로그인하세요")
            else -> throw IllegalStateException(error(response))
        }
    }

    companion object {
        /** 서버의 웹 세션 쿠키 이름 (app-server `SESSION_COOKIE`) */
        const val COOKIE = "vesta_session"

        fun file(paths: VestaPaths = VestaPaths.default()): Path = paths.home.resolve("remote.json")

        fun load(path: Path = file()): RemoteLogin? =
            runCatching { ApiJson.decodeFromString(serializer(), Files.readString(path)) }.getOrNull()

        /** 권한 600으로 쓴다 (POSIX가 아니면, 예를 들어 Windows는 사용자 폴더 권한에 맡긴다) */
        fun save(login: RemoteLogin, path: Path = file()) {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("remote.json.tmp")
            Files.deleteIfExists(tmp)
            runCatching { Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
                .getOrElse { Files.createFile(tmp) }
            Files.writeString(tmp, ApiJson.encodeToString(serializer(), login))
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }

        fun delete(path: Path = file()) = Files.deleteIfExists(path)

        /** 서버 주소를 정리한다 (끝 `/` 제거). 쓸 수 없으면 그 이유 */
        fun normalize(raw: String): Result<String> = runCatching {
            val text = raw.trim().removeSuffix("/")
            val uri = runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull()
                ?: throw IllegalArgumentException("주소 형식이 아닙니다: $raw")
            val host = uri.host?.lowercase() ?: throw IllegalArgumentException("주소에 호스트가 없습니다: $raw")
            require(uri.scheme == "https" || uri.scheme == "http") { "https 주소를 주세요: $raw" }
            require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) { "경로 없이 서버 주소만 주세요 (예: https://vesta.example.com)" }
            if (uri.scheme == "http") {
                require(host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]" || host.endsWith(".ts.net")) {
                    "비밀번호가 그대로 보이는 http 주소는 받지 않습니다. https 주소를 주세요 (이 기기·Tailscale *.ts.net만 http 허용)"
                }
            }
            "${uri.scheme}://${uri.rawAuthority.lowercase()}"
        }

        /** 로그인해서 세션을 받는다. 실패하면 서버가 준 이유 */
        suspend fun login(url: String, name: String?, password: String): Result<RemoteLogin> = runCatching {
            client(timeoutMillis = 30_000).use { http ->
                val response = runCatching {
                    http.post("$url/api/login") {
                        contentType(ContentType.Application.Json)
                        setBody(ApiJson.encodeToString(LoginDto.serializer(), LoginDto(password, name)))
                    }
                }.getOrElse { throw IllegalStateException("서버에 닿지 않습니다: $url (${it.message ?: it::class.simpleName})") }
                if (!response.status.isSuccess()) throw IllegalStateException(error(response))
                val me = ApiJson.decodeFromString(MeDto.serializer(), response.bodyAsText())
                val session = sessionCookie(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
                    ?: throw IllegalStateException("서버가 로그인 쿠키를 주지 않았습니다 (Vesta 서버 주소가 맞는지 확인하세요)")
                RemoteLogin(url, me.name, session, me.csrf)
            }
        }

        /** 서버의 세션을 지운다 (실패해도 괜찮다: 로컬 파일은 부르는 쪽이 지운다) */
        suspend fun logout(login: RemoteLogin): Boolean = runCatching {
            client(timeoutMillis = 10_000).use { http -> http.post("${login.url}/api/logout") { login.applyTo(this) }.status.isSuccess() }
        }.getOrDefault(false)

        /** `Set-Cookie` 줄들에서 세션 쿠키 값 */
        internal fun sessionCookie(headers: List<String>): String? = headers.asSequence()
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }

        fun roleName(role: String) = when (role) {
            "OWNER" -> "주인"
            "MEMBER" -> "멤버"
            "GUEST" -> "손님"
            else -> role
        }

        private fun client(timeoutMillis: Long) = HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = timeoutMillis }
        }

        private suspend fun error(response: HttpResponse): String =
            runCatching { ((ApiJson.parseToJsonElement(response.bodyAsText()) as JsonObject)["error"] as JsonPrimitive).content }.getOrNull()
                ?: "서버 오류 ${response.status.value}"
    }
}
