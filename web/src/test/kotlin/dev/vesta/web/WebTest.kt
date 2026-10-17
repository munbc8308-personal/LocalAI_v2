package dev.vesta.web

import dev.vesta.core.config.WebConfig
import dev.vesta.core.web.FetchResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.InetAddress

class WebTest : FunSpec({
    val article = """
        <html><head><title>사이트 | 글 제목</title><meta property="og:title" content="Kotlin 2.4 정리"></head>
        <body>
          <nav><a href="/">홈</a> 메뉴 메뉴 메뉴</nav>
          <article>
            <h1>Kotlin 2.4 정리</h1>
            <p>${"Kotlin 2.4에서는 K2 컴파일러가 기본이다. ".repeat(10)}</p>
            <ul><li>새 기능 A</li><li>새 기능 B</li></ul>
            <pre>fun main() = println("hi")</pre>
          </article>
          <footer>저작권 광고</footer>
          <script>track()</script>
        </body></html>
    """.trimIndent()

    test("본문 추출: og 제목, 메뉴·푸터·스크립트 제거, 목록·코드 유지") {
        val content = ContentExtractor.extract(article, "https://example.com/post")
        content.title shouldBe "Kotlin 2.4 정리"
        content.text shouldContain "K2 컴파일러"
        content.text shouldContain "- 새 기능 A"
        content.text shouldContain "println(\"hi\")"
        content.text shouldNotContain "메뉴"
        content.text shouldNotContain "저작권"
        content.text shouldNotContain "track()"
    }

    test("robots.txt: 우리 봇 규칙이 있으면 그것, 없으면 * 규칙") {
        val text = """
            User-agent: *
            Disallow: /private

            User-agent: VestaBot
            Disallow: /no-bots
        """.trimIndent()
        Robots.disallowed(text, "vestabot") shouldBe listOf("/no-bots")
        Robots.disallowed("User-agent: *\nDisallow: /a\nDisallow: /b", "vestabot") shouldBe listOf("/a", "/b")
    }

    /** DNS 가짜: example.com은 공인 IP, rebind.test는 루프백(127.0.0.1.nip.io 같은 우회 흉내) */
    val dns: suspend (String) -> List<InetAddress> = { host ->
        when (host) {
            "example.com", "www.example.com" -> listOf(InetAddress.getByAddress(host, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            "rebind.test" -> listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
            else -> listOf(InetAddress.getByName(host))
        }
    }

    fun fetcher(config: WebConfig = WebConfig()) = HttpPageFetcher(
        HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/robots.txt" -> respond("User-agent: *\nDisallow: /secret", HttpStatusCode.OK)
                "/to-internal" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1:11434/api/tags"))
                "/to-post" -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/post"))
                "/loop" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/loop"))
                else -> respond(article, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
            }
        }) { followRedirects = false },
        config,
        cache = null,
        guard = UrlGuard(config.blockedDomains, dns),
    )

    test("페이지를 받아 본문을 돌려준다") {
        val result = fetcher().fetch("https://example.com/post").shouldBeInstanceOf<FetchResult.Ok>()
        result.page.title shouldBe "Kotlin 2.4 정리"
    }

    test("robots.txt 금지 경로, 차단 도메인, 내부 주소는 읽지 않는다") {
        fetcher().fetch("https://example.com/secret/x").shouldBeInstanceOf<FetchResult.Failed>().reason shouldContain "robots"
        fetcher(WebConfig(blockedDomains = listOf("example.com"))).fetch("https://www.example.com/post")
            .shouldBeInstanceOf<FetchResult.Failed>().reason shouldBe "차단 도메인"
        fetcher().fetch("http://127.0.0.1:11434/api/tags").shouldBeInstanceOf<FetchResult.Failed>().reason shouldContain "내부"
        fetcher().fetch("http://192.168.0.1/").shouldBeInstanceOf<FetchResult.Failed>()
    }

    test("SSRF: 이름이 내부 IP로 해석되거나, 리다이렉트로 내부로 가면 막는다") {
        fetcher().fetch("http://rebind.test/post").shouldBeInstanceOf<FetchResult.Failed>().reason shouldContain "내부"
        fetcher().fetch("https://example.com/to-internal").shouldBeInstanceOf<FetchResult.Failed>().reason shouldContain "리다이렉트 차단"
        fetcher().fetch("https://user:pw@example.com/post").shouldBeInstanceOf<FetchResult.Failed>()
        fetcher().fetch("https://example.com/loop").shouldBeInstanceOf<FetchResult.Failed>().reason shouldContain "너무 많음"
    }

    test("안전한 리다이렉트는 따라간다") {
        fetcher().fetch("https://example.com/to-post").shouldBeInstanceOf<FetchResult.Ok>().page.title shouldBe "Kotlin 2.4 정리"
    }

    test("내부 주소 판정") {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.1.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1")
            .forEach { UrlGuard.isInternal(InetAddress.getByName(it)) shouldBe true }
        listOf("8.8.8.8", "93.184.216.34", "2606:4700::1111").forEach { UrlGuard.isInternal(InetAddress.getByName(it)) shouldBe false }
    }
})
