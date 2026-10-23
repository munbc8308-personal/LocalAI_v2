package dev.vesta.a2a

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A2A용 HTTP 클라이언트 (M22 보안 검토). 바깥 에이전트용은 **연결할 때마다** DNS 결과를 검사해 내부 주소(이 Mac·사설망·Tailscale)면
 * 연결하지 않는다: 검사한 주소와 실제 연결 주소가 같다 (DNS rebinding으로 카드·연결 주소를 내부 서비스로 바꾸지 못하게).
 * 내부 에이전트용은 사용자가 "이 Mac·내 네트워크의 에이전트"라고 직접 고른 경우에만 쓴다. 둘 다 리다이렉트는 따라가지 않는다
 */
object A2aHttp {
    fun external(internal: (InetAddress) -> Boolean, timeoutSeconds: Long = 60): HttpClient = client(PinnedDns(internal), LiteralGuard(internal), timeoutSeconds)

    fun local(timeoutSeconds: Long = 60): HttpClient = client(okhttp3.Dns.SYSTEM, null, timeoutSeconds)

    private fun client(dns: okhttp3.Dns, guard: okhttp3.Interceptor?, timeoutSeconds: Long) = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        engine {
            config {
                dns(dns)
                guard?.let { addInterceptor(it) }
                followRedirects(false)
                followSslRedirects(false)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = timeoutSeconds * 1_000
            socketTimeoutMillis = timeoutSeconds * 1_000
        }
    }

    /** OkHttp는 IP 주소(`http://127.0.0.1:…`)에는 DNS를 부르지 않으므로 따로 막는다 */
    class LiteralGuard(private val internal: (InetAddress) -> Boolean) : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            literal(chain.request().url.host)?.let { if (internal(it)) throw java.io.IOException("내부 주소라 연결하지 않음: ${chain.request().url.host}") }
            return chain.proceed(chain.request())
        }
    }

    /** IP 주소 글자면 그 주소 (DNS를 부르지 않는다), 이름이면 null */
    fun literal(host: String): InetAddress? {
        val h = host.removePrefix("[").removeSuffix("]")
        val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return if (v4.matches(h) || (':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' })) runCatching { InetAddress.getByName(h) }.getOrNull() else null
    }

    /** 해석한 주소 중 하나라도 내부면 연결하지 않는다 */
    class PinnedDns(
        private val internal: (InetAddress) -> Boolean,
        private val resolve: (String) -> List<InetAddress> = { okhttp3.Dns.SYSTEM.lookup(it) },
    ) : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = hostname.lowercase().trimEnd('.')
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) throw UnknownHostException("내부 주소라 연결하지 않음: $hostname")
            val addresses = resolve(hostname)
            if (addresses.isEmpty()) throw UnknownHostException(hostname)
            if (addresses.any(internal)) throw UnknownHostException("내부 주소로 해석되어 연결하지 않음: $hostname")
            return addresses
        }
    }
}
