package dev.vesta.a2a

import dev.vesta.web.GuardedNetwork
import io.ktor.client.HttpClient
import java.net.InetAddress

/**
 * A2A용 HTTP 클라이언트 (M22 보안 검토). 바깥 에이전트용은 **연결할 때마다** DNS 결과를 검사해 내부 주소(이 Mac·사설망·Tailscale)면
 * 연결하지 않는다: 검사한 주소와 실제 연결 주소가 같다 (DNS rebinding으로 카드·연결 주소를 내부 서비스로 바꾸지 못하게, [GuardedNetwork]).
 * 내부 에이전트용은 사용자가 "이 Mac·내 네트워크의 에이전트"라고 직접 고른 경우에만 쓴다. 둘 다 리다이렉트는 따라가지 않는다
 */
object A2aHttp {
    fun external(internal: (InetAddress) -> Boolean, timeoutSeconds: Long = 60): HttpClient =
        GuardedNetwork.client(GuardedNetwork.PinnedDns(internal), GuardedNetwork.GuardedSocketFactory(internal), timeoutSeconds)

    fun local(timeoutSeconds: Long = 60): HttpClient = GuardedNetwork.client(okhttp3.Dns.SYSTEM, null, timeoutSeconds)
}
