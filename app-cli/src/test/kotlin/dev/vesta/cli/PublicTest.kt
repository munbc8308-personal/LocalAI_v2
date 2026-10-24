package dev.vesta.cli

import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.PublicAccessConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PublicTest : FunSpec({
    test("설정 파일의 publicAccess 묶음만 바꾸고, 다른 설정·주석은 그대로 둔다") {
        val text = """
            server:
              port: 8787

            # 지인 공개
            publicAccess:
              enabled: false
              port: 8788

            # 자가 개선
            evolution:
              enabled: true
        """.trimIndent() + "\n"
        val out = replaceTopLevelBlock(text, "publicAccess", "publicAccess:\n  enabled: true\n")
        out shouldContain "server:\n  port: 8787"
        out shouldContain "# 지인 공개\npublicAccess:\n  enabled: true\n\n# 자가 개선\nevolution:"
        out shouldNotContain "8788"
        replaceTopLevelBlock("server:\n  port: 1\n", "publicAccess", "publicAccess:\n  enabled: true\n") shouldBe "server:\n  port: 1\n\npublicAccess:\n  enabled: true\n"
    }

    test("저장한 공개 설정을 다시 읽을 수 있다") {
        val file = createTempDirectory("vesta").resolve("config.yaml")
        file.writeText(javaClass.getResource("/default-config.yaml")!!.readText())
        val config = PublicAccessConfig(true, 8788, "vesta.example.com", "family.cloudflareaccess.com", "aud-tag", false)
        writePublicConfig(file, config)
        ConfigLoader.load(file, tunedFile = null).publicAccess shouldBe config
        file.readText() shouldContain "evolution:"
    }

    test("임시 비밀번호: 헷갈리는 글자 없이 xxxx-xxxx-xxxx") {
        repeat(20) {
            val p = temporaryPassword()
            p.matches(Regex("^[a-hj-km-np-z2-9]{4}-[a-hj-km-np-z2-9]{4}-[a-hj-km-np-z2-9]{4}$")) shouldBe true
        }
    }

    test("Access 허용 목록: 정책이 없으면 만들고, 있으면 이메일 목록으로 덮어쓴다") {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var policies = """{"success":true,"result":[]}"""
        val cf = CloudflareAccess("unused") { method, path, body ->
            calls += Triple(method, path, body)
            200 to when {
                path == "/accounts" -> """{"success":true,"result":[{"id":"acc","name":"home"}]}"""
                path.endsWith("/organizations") -> """{"success":true,"result":{"auth_domain":"family.cloudflareaccess.com"}}"""
                path.endsWith("/access/apps") && method == "GET" -> """{"success":true,"result":[{"id":"app1","aud":"aud-tag","domain":"vesta.example.com"}]}"""
                path.endsWith("/policies") && method == "GET" -> policies
                else -> """{"success":true,"result":{"id":"p1"}}"""
            }
        }
        val (account) = cf.account()
        cf.teamDomain(account) shouldBe "family.cloudflareaccess.com"
        val app = cf.findApp(account, "Vesta.Example.com")!!
        app.aud shouldBe "aud-tag"
        cf.syncEmails(account, app, listOf("b@x.com", "a@x.com"))
        calls.last().first shouldBe "POST"
        calls.last().third!! shouldContain """"include":[{"email":{"email":"a@x.com"}},{"email":{"email":"b@x.com"}}]"""
        policies = """{"success":true,"result":[{"id":"p1","name":"${CloudflareAccess.POLICY}","include":[{"email":{"email":"a@x.com"}}]}]}"""
        cf.emails(account, app) shouldContainExactly listOf("a@x.com")
        cf.syncEmails(account, app, listOf("a@x.com"))
        calls.last().first shouldBe "PUT"
        calls.last().second shouldBe "/accounts/acc/access/apps/app1/policies/p1"
        cf.syncEmails(account, app, emptyList())
        calls.last().first shouldBe "DELETE"
    }

    test("Cloudflare 오류는 메시지와 함께 알린다") {
        val cf = CloudflareAccess("unused") { _, _, _ -> 403 to """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""" }
        val error = runCatching { cf.account() }.exceptionOrNull()
        (error is CloudflareException) shouldBe true
        error!!.message!! shouldContain "Authentication error"
    }
})
