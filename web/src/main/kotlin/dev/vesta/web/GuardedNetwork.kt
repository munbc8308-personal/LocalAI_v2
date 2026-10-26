package dev.vesta.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 바깥으로 나가는 HTTP의 연결 시점 검사 (A2A M22 보안 검토, 웹 수집은 M34 Q-01). [UrlGuard]는 요청 전에 검사만 해서,
 * 검사와 연결 사이에 DNS 응답이 바뀌면(DNS rebinding) 내부 주소로 연결될 수 있었다. 여기서는 **연결할 때마다** 실제로 거는 주소를 본다
 */
object GuardedNetwork {
    /** 웹 페이지 수집용: 연결 시점 검사 + 리다이렉트는 따라가지 않는다(수집기가 한 번씩 검사하며 따라간다) */
    fun webClient(timeoutSeconds: Long, internal: (InetAddress) -> Boolean = UrlGuard::isInternal): HttpClient = client(PinnedDns(internal), GuardedSocketFactory(internal), timeoutSeconds, connectSeconds = 5)

    /** [dns]·[sockets]를 쓰는 OkHttp 클라이언트. 리다이렉트는 따라가지 않는다 */
    fun client(dns: okhttp3.Dns, sockets: javax.net.SocketFactory?, timeoutSeconds: Long, connectSeconds: Long = 5): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        engine {
            config {
                dns(dns)
                sockets?.let { socketFactory(it) }
                followRedirects(false)
                followSslRedirects(false)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = connectSeconds * 1_000
            requestTimeoutMillis = timeoutSeconds * 1_000
            socketTimeoutMillis = timeoutSeconds * 1_000
        }
    }

    /**
     * 실제로 연결하는 주소를 소켓에서 검사한다 (M22 보안 검토). 호스트 글자를 어떻게 해석하든(IP 글자·10진수·8진수 표기, DNS) 마지막에 거는 주소가 내부면 막는다.
     * [PinnedDns]는 이름일 때 먼저 알기 쉬운 오류를 내는 용도이고, 막는 것은 이 소켓이다
     */
    class GuardedSocketFactory(private val internal: (InetAddress) -> Boolean) : javax.net.SocketFactory() {
        override fun createSocket(): java.net.Socket = Guarded(internal)
        override fun createSocket(host: String, port: Int) = createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
            createSocket().apply { bind(java.net.InetSocketAddress(localHost, localPort)); connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
            createSocket().apply { bind(java.net.InetSocketAddress(localAddress, localPort)); connect(java.net.InetSocketAddress(address, port)) }

        private class Guarded(private val internal: (InetAddress) -> Boolean) : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                val address = (endpoint as? java.net.InetSocketAddress)?.address
                if (address == null || internal(address)) throw java.io.IOException("내부 주소라 연결하지 않음: $endpoint")
                super.connect(endpoint, timeout)
            }
        }
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
