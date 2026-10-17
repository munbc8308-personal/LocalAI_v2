package dev.vesta.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * 외부 URL만 읽도록 막는 검사 (SSRF 방지).
 * 웹 페이지·검색 결과의 링크로 이 Mac의 내부 서비스(Ollama :11434, SearXNG :8888, 공유기 관리 화면 등)를 읽게 만드는 공격을 막는다.
 * 호스트 이름이 아니라 **DNS로 해석한 모든 IP**를 검사한다 (`127.0.0.1.nip.io`, 10진수 IP 같은 우회 방지).
 * 리다이렉트는 HttpPageFetcher가 한 번씩 따라가며 매번 이 검사를 다시 한다.
 *
 * 남은 위험: 검사와 실제 연결 사이에 DNS 응답이 바뀌는 DNS rebinding은 완전히 막지 못한다 (연결 IP 고정 필요, PROJECT.md 14장 리스크).
 */
class UrlGuard(
    private val blockedDomains: List<String>,
    private val resolve: suspend (String) -> List<InetAddress> = { host ->
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
    },
) {
    /** 문제가 있으면 이유, 없으면 null. */
    suspend fun reject(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "잘못된 URL"
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return "http(s)만 지원"
        if (uri.userInfo != null) return "사용자 정보가 든 URL은 읽지 않음"
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return "호스트 없음"
        if (blockedDomains.any { host == it || host.endsWith(".$it") }) return "차단 도메인"
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return "내부 네트워크 주소는 읽지 않음"
        }
        val addresses = runCatching { resolve(host) }.getOrElse { return "주소를 찾을 수 없음" }
        if (addresses.isEmpty()) return "주소를 찾을 수 없음"
        if (addresses.any(::isInternal)) return "내부 네트워크 주소는 읽지 않음"
        return null
    }

    companion object {
        fun isInternal(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return true
            val b = address.address.map { it.toInt() and 0xff }
            return when (address) {
                is Inet4Address ->
                    b[0] == 0 ||                                   // 0.0.0.0/8
                        (b[0] == 100 && b[1] in 64..127) ||        // 100.64.0.0/10 CGNAT (Tailscale 대역)
                        (b[0] == 192 && b[1] == 0 && b[2] == 0) || // 192.0.0.0/24
                        (b[0] == 198 && b[1] in 18..19) ||         // 198.18.0.0/15 벤치마크망
                        b[0] >= 240                                // 240.0.0.0/4 예약
                is Inet6Address ->
                    (b[0] and 0xfe) == 0xfc ||                     // fc00::/7 ULA
                        isMappedInternalV4(b)
                else -> true
            }
        }

        /** ::ffff:127.0.0.1 같은 IPv4 매핑 주소 */
        private fun isMappedInternalV4(b: List<Int>): Boolean {
            val mapped = b.take(10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff
            return mapped && isInternal(InetAddress.getByAddress(b.drop(12).map { it.toByte() }.toByteArray()))
        }
    }
}
