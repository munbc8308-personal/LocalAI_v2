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
    fun external(internal: (InetAddress) -> Boolean, timeoutSeconds: Long = 60): HttpClient = client(PinnedDns(internal), GuardedSocketFactory(internal), timeoutSeconds)

    fun local(timeoutSeconds: Long = 60): HttpClient = client(okhttp3.Dns.SYSTEM, null, timeoutSeconds)

    private fun client(dns: okhttp3.Dns, sockets: javax.net.SocketFactory?, timeoutSeconds: Long) = HttpClient(OkHttp) {
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
            connectTimeoutMillis = 5_000
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
