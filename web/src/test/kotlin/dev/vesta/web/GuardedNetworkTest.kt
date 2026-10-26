package dev.vesta.web

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import java.net.InetAddress

class GuardedNetworkTest : FunSpec({
    test("웹 수집 클라이언트는 연결 순간에 내부 주소를 막는다 (Q-01: 검사 뒤 DNS가 바뀌어도)") {
        val http = GuardedNetwork.webClient(5)
        // IP 글자로 바로 거는 경우: 소켓이 막는다
        shouldThrowAny { http.get("http://127.0.0.1:1/") }.message.orEmpty() shouldContain "내부 주소"
        // 검사 때는 바깥 주소였다가 연결 때 내부로 바뀌는 이름: 연결에 쓰는 해석 결과로 막는다
        var answer = InetAddress.getByName("93.184.215.14")
        val rebinding = GuardedNetwork.PinnedDns(UrlGuard::isInternal) { listOf(answer) }
        rebinding.lookup("rebind.example")
        answer = InetAddress.getByName("127.0.0.1")
        shouldThrowAny { rebinding.lookup("rebind.example") }.message.orEmpty() shouldContain "내부 주소"
    }
})
