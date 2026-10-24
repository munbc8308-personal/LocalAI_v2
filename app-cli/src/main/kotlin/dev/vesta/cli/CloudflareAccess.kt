package dev.vesta.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CloudflareException(message: String) : RuntimeException(message)

/**
 * Cloudflare Access 관리 (PROJECT.md 7.20 P-02·P-03, M26). API 토큰은 키체인 `dev.vesta.cloudflare`/`api-token`.
 * 토큰 권한: 계정 > Access: Apps and Policies 편집, Access: Organizations, Identity Providers, and Groups 읽기.
 * 허용 목록의 원본은 Vesta 사용자 이메일이다: 초대·삭제·이메일 바꾸기 때 정책 하나("Vesta 초대")를 그 목록으로 덮어쓴다.
 */
class CloudflareAccess(
    private val token: String,
    /** (메서드, 경로, 본문) → (상태, 본문). 테스트에서는 가짜 */
    private val send: (String, String, String?) -> Pair<Int, String> = defaultSend(token),
) {
    data class App(val id: String, val aud: String, val domain: String)

    /** 토큰으로 볼 수 있는 계정이 하나면 그것, 여럿이면 [name]과 같은 것 */
    fun account(name: String? = null): Pair<String, String> {
        val accounts = results(call("GET", "/accounts")).map { it.str("id") to it.str("name") }
        if (accounts.isEmpty()) throw CloudflareException("이 토큰으로 볼 수 있는 계정이 없습니다")
        if (name != null) return accounts.firstOrNull { it.second == name } ?: throw CloudflareException("없는 계정: $name (있는 계정: ${accounts.joinToString { it.second }})")
        if (accounts.size > 1) throw CloudflareException("계정이 여럿입니다. --account 로 고르세요: ${accounts.joinToString { it.second }}")
        return accounts.single()
    }

    /** Zero Trust 팀 도메인 (`<팀>.cloudflareaccess.com`). Zero Trust를 아직 켜지 않았으면 안내 */
    fun teamDomain(account: String): String {
        val org = call("GET", "/accounts/$account/access/organizations")["result"] as? JsonObject
        return org?.str("auth_domain")?.takeIf { it.isNotBlank() }
            ?: throw CloudflareException("Zero Trust가 아직 없습니다. 대시보드 Zero Trust에서 팀 이름을 정하고 Free 요금제를 고르세요")
    }

    fun findApp(account: String, hostname: String): App? =
        results(call("GET", "/accounts/$account/access/apps")).firstOrNull { it.str("domain").equals(hostname, ignoreCase = true) }?.toApp()

    /** 공개 주소 앞에 로그인 관문 (self-hosted 앱). 로그인은 1주일 유지 */
    fun createApp(account: String, hostname: String): App {
        val body = buildJsonObject {
            put("name", "Vesta")
            put("domain", hostname)
            put("type", "self_hosted")
            put("session_duration", "168h")
            put("app_launcher_visible", false)
        }
        return (call("POST", "/accounts/$account/access/apps", body)["result"] as? JsonObject)?.toApp()
            ?: throw CloudflareException("Access 앱을 만들지 못했습니다")
    }

    /** 허용 정책을 이 이메일 목록으로 덮어쓴다. 정책이 없으면 만든다. 목록이 비면 아무도 못 들어오게 정책을 지운다 */
    fun syncEmails(account: String, app: App, emails: Collection<String>) {
        val path = "/accounts/$account/access/apps/${app.id}/policies"
        val existing = results(call("GET", path)).firstOrNull { it.str("name") == POLICY }?.str("id")
        if (emails.isEmpty()) {
            existing?.let { call("DELETE", "$path/$it") }
            return
        }
        val body = buildJsonObject {
            put("name", POLICY)
            put("decision", "allow")
            put("precedence", 1)
            putJsonArray("include") { emails.sorted().forEach { email -> addJsonObject { putJsonObject("email") { put("email", email) } } } }
        }
        if (existing == null) call("POST", path, body) else call("PUT", "$path/$existing", body)
    }

    /** 지금 허용된 이메일 */
    fun emails(account: String, app: App): List<String> =
        results(call("GET", "/accounts/$account/access/apps/${app.id}/policies")).firstOrNull { it.str("name") == POLICY }
            ?.get("include")?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("email")?.jsonObject?.str("email") }

    private fun call(method: String, path: String, body: JsonElement? = null): JsonObject {
        val (status, text) = send(method, path, body?.toString())
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw CloudflareException("Cloudflare 응답을 읽지 못했습니다 (HTTP $status)")
        if (status !in 200..299 || (json["success"] as? JsonPrimitive)?.booleanOrNull == false) {
            val errors = (json["errors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("message") }
            throw CloudflareException("Cloudflare API 오류 (HTTP $status): ${errors.joinToString().ifBlank { "알 수 없음" }}")
        }
        return json
    }

    private fun results(json: JsonObject): List<JsonObject> = (json["result"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.toApp() = App(str("id"), str("aud"), str("domain"))

    companion object {
        const val POLICY = "Vesta 초대"
        const val KEYCHAIN = "cloudflare"
        const val ACCOUNT = "api-token"
        private const val API = "https://api.cloudflare.com/client/v4"

        private fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

        fun defaultSend(token: String): (String, String, String?) -> Pair<Int, String> {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            return { method, path, body ->
                val request = HttpRequest.newBuilder(URI("$API$path")).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                response.statusCode() to response.body()
            }
        }
    }
}
