package dev.vesta.google

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.tools.ToolContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GoogleTest : FunSpec({
    val json = headersOf(HttpHeaders.ContentType, "application/json")
    val user = UserId(7)

    class MemoryStore : GoogleTokenStore {
        val saved = mutableMapOf<Long, GoogleCredentials>()
        override fun read(user: UserId) = saved[user.value]
        override fun write(user: UserId, credentials: GoogleCredentials): Boolean { saved[user.value] = credentials; return true }
        override fun delete(user: UserId) { saved.remove(user.value) }
    }

    fun body(request: HttpRequestData) = when (val b = request.body) {
        is OutgoingContent.ByteArrayContent -> String(b.bytes())
        else -> b.toString()
    }

    fun docx(vararg paragraphs: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val ps = paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
            zip.write("""<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$ps</w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    test("연결: state는 한 번만·사용자에 묶이고, PKCE, 토큰 교환 후 저장, 만료되면 갱신") {
        var clock = 1_000_000L
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.url.encodedPath} ${body(request)}"
            when (request.url.toString()) {
                GoogleAuth.TOKEN_URL -> if ("refresh_token=r-1" in body(request)) respond("""{"access_token":"a-2","expires_in":3600}""", headers = json)
                    else respond("""{"access_token":"a-1","refresh_token":"r-1","expires_in":3600}""", headers = json)
                GoogleAuth.USERINFO_URL -> respond("""{"email":"mina@gmail.com"}""", headers = json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val store = MemoryStore()
        val auth = GoogleAuth(HttpClient(engine), "client-1", { "test-client-secret" }, listOf("https://vesta.example/api/google/callback", "http://localhost:8787/api/google/callback"), store, now = { clock })
        auth.start(user, "https://evil.example") shouldBe null
        auth.start(user, "http://localhost:8787")!!.redirectUri shouldBe "http://localhost:8787/api/google/callback"
        val start = auth.start(user, "https://vesta.example")!!
        val link = Url(start.url)
        link.parameters["code_challenge_method"] shouldBe "S256"
        link.parameters["access_type"] shouldBe "offline"
        link.parameters["redirect_uri"] shouldBe "https://vesta.example/api/google/callback"
        val state = link.parameters["state"]!!

        auth.finish("other-state", "code-1", start.nonce).shouldBeInstanceOf<GoogleAuth.Finish.Failed>()
        // 남이 보낸 링크: 시작한 브라우저의 쿠키(nonce)가 없으면 안 된다 (state도 버려진다)
        val stolen = auth.start(user, "https://vesta.example")!!
        auth.finish(Url(stolen.url).parameters["state"]!!, "code-1", null).shouldBeInstanceOf<GoogleAuth.Finish.Failed>()
        val again = auth.start(user, "https://vesta.example")!!
        auth.finish(Url(again.url).parameters["state"]!!, "code-1", "wrong-nonce").shouldBeInstanceOf<GoogleAuth.Finish.Failed>()
        val real = auth.start(user, "https://vesta.example")!!
        auth.finish(Url(real.url).parameters["state"]!!, "code-1", real.nonce) shouldBe GoogleAuth.Finish.Ok(user, "mina@gmail.com")
        requests.first() shouldContain "code_verifier="
        store.saved[7]!!.refreshToken shouldBe "r-1"
        auth.email(user) shouldBe "mina@gmail.com"
        // 같은 state는 다시 못 쓴다 (새 요청이 앞 요청을 버렸다)
        auth.finish(state, "code-1", start.nonce).shouldBeInstanceOf<GoogleAuth.Finish.Failed>()

        auth.accessToken(user) shouldBe "a-1"
        clock += 3_600_000
        auth.accessToken(user) shouldBe "a-2"
        auth.accessToken(UserId(8)) shouldBe null

        // 10분이 지난 state는 안 된다
        val late = auth.start(user, "https://vesta.example")!!
        clock += GoogleAuth.STATE_MILLIS + 1
        auth.finish(Url(late.url).parameters["state"]!!, "code-2", late.nonce).shouldBeInstanceOf<GoogleAuth.Finish.Failed>()

        auth.disconnect(user)
        auth.email(user) shouldBe null
        store.saved.isEmpty() shouldBe true
    }

    fun connected(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData): GoogleApi {
        val store = MemoryStore().apply { saved[7] = GoogleCredentials("mina@gmail.com", "r-1") }
        val http = HttpClient(MockEngine { request ->
            if (request.url.toString() == GoogleAuth.TOKEN_URL) respond("""{"access_token":"a-1","expires_in":3600}""", headers = json) else handler(request)
        })
        return GoogleApi(http, GoogleAuth(http, "client-1", { "test-client-secret" }, listOf("https://x/cb"), store))
    }
    val member = ToolContext(UserContext(user, UserRole.MEMBER))

    test("Gmail 보내기: 줄바꿈으로 헤더를 끼워 넣지 못하고, 승인 화면에 받는 사람·본문 전부") {
        var sent: String? = null
        val api = connected { request -> sent = body(request); respond("""{"id":"m-1"}""", headers = json) }
        val tool = googleTools(api, 10_000).first { it.spec.name == "gmail_send" }
        val args = buildJsonObject { put("to", "friend@example.com"); put("subject", "점심"); put("body", "12시 어때?") }
        tool.describe(args).body shouldContain "12시 어때?"
        tool.describe(args).warning!! shouldContain "실제로 보내집니다"
        tool.execute(args, member).output shouldContain "보냈다"
        val raw = Regex("\"raw\":\"([^\"]+)\"").find(sent!!)!!.groupValues[1]
        val mime = String(Base64.getUrlDecoder().decode(raw))
        mime shouldContain "To: friend@example.com\r\n"
        mime shouldContain "=?UTF-8?B?"

        val injected = tool.execute(buildJsonObject { put("to", "a@example.com\r\nBcc: evil@example.com"); put("body", "x") }, member)
        injected.isError shouldBe true
        GmailComposeTool.addresses("not-an-address") shouldBe null
        shouldThrow<IllegalArgumentException> { GmailComposeTool.mime("a@b.c", null, "제목\nBcc: x@y.z", "본문", null) }
    }

    test("드라이브 읽기: 링크에서 id, .docx는 글로, Google 문서는 내보내기") {
        val api = connected { request ->
            when {
                request.url.encodedPath.endsWith("/files/doc_12345") && request.url.parameters["alt"] == "media" ->
                    respond(docx("첫 문단", "둘째 문단"), headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                request.url.encodedPath.endsWith("/files/doc_12345") -> respond("""{"id":"doc_12345","name":"보고서.docx","mimeType":"${OfficeText.DOCX}"}""", headers = json)
                request.url.encodedPath.endsWith("/files/gdoc_12345/export") -> respond("Google 문서 본문", headers = headersOf(HttpHeaders.ContentType, "text/plain"))
                request.url.encodedPath.endsWith("/files/gdoc_12345") -> respond("""{"id":"gdoc_12345","name":"회의록","mimeType":"application/vnd.google-apps.document"}""", headers = json)
                else -> respond("""{"error":{"message":"없음"}}""", HttpStatusCode.NotFound, json)
            }
        }
        val read = googleTools(api, 10_000).first { it.spec.name == "drive_read" }
        read.execute(buildJsonObject { put("file", "https://drive.google.com/file/d/doc_12345/view") }, member).output shouldContain "첫 문단\n둘째 문단"
        read.execute(buildJsonObject { put("file", "gdoc_12345") }, member).output shouldContain "Google 문서 본문"
        read.execute(buildJsonObject { put("file", "../../etc") }, member).isError shouldBe true
        read.execute(buildJsonObject { put("file", "missing_123") }, member).output shouldContain "Google 오류 404: 없음"
    }

    test("연결하지 않았거나 손님이면 쓰지 못한다") {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) })
        val api = GoogleApi(http, GoogleAuth(http, "c", { "s" }, listOf("https://x/cb"), MemoryStore()))
        val search = googleTools(api, 10_000).first { it.spec.name == "gmail_search" }
        search.execute(buildJsonObject {}, member).output shouldContain "연결되지 않았거나"
        search.execute(buildJsonObject {}, ToolContext(UserContext(user, UserRole.GUEST))).output shouldContain "손님"
        search.readsLocalData shouldBe true
        googleTools(api, 10_000).first { it.spec.name == "sheets_append" }.readsLocalData shouldBe false
    }

    test("시트 줄 추가: 값은 줄 배열, 범위는 경로에 안전하게") {
        var path = ""
        var sent = ""
        val api = connected { request -> path = request.url.encodedPath; sent = body(request); respond("""{"updates":{"updatedRange":"가계부!A5:B5"}}""", headers = json) }
        val tool = googleTools(api, 10_000).first { it.spec.name == "sheets_append" }
        val values = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("10/29")); add(JsonPrimitive(12000)) }) }
        tool.execute(buildJsonObject { put("spreadsheet", "sheet_12345"); put("range", "가계부"); put("values", values) }, member).output shouldContain "가계부!A5:B5"
        path shouldContain "/values/"
        path shouldNotContain "가계부"
        // 범위로 경로를 늘리지 못한다
        tool.execute(buildJsonObject { put("spreadsheet", "sheet_12345"); put("range", "../../drive/v3/files"); put("values", values) }, member).isError shouldBe true
        sent shouldContain "\"12000\""
    }

    test("날짜: 날짜만·시각·시간대") {
        val seoul = ZoneId.of("Asia/Seoul")
        time("2026-10-30", seoul).toString() shouldBe "2026-10-30T00:00+09:00[Asia/Seoul]"
        time("2026-10-30", seoul, endOfDay = true).toLocalDate().toString() shouldBe "2026-10-31"
        time("2026-10-30 15:00", seoul).hour shouldBe 15
        time("2026-10-30T06:00:00Z", seoul).hour shouldBe 15
        shouldThrow<IllegalArgumentException> { time("내일", seoul) }
    }

    test("Office 글: .xlsx 칸, DTD가 든 XML은 거부") {
        val xlsx = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zip.write("""<sst><si><t>이름</t></si><si><t>민아</t></si></sst>""".toByteArray())
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write("""<worksheet><sheetData><row><c t="s"><v>0</v></c><c><v>3</v></c></row><row><c t="s"><v>1</v></c><c><v>42</v></c></row></sheetData></worksheet>""".toByteArray())
            }
        }.toByteArray()
        OfficeText.xlsx(xlsx) shouldContain "이름\t3\n민아\t42"
        val evil = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><w:document xmlns:w="w"><w:p><w:t>&e;</w:t></w:p></w:document>""".toByteArray())
            }
        }.toByteArray()
        shouldThrow<Exception> { OfficeText.docx(evil) }
    }
})
