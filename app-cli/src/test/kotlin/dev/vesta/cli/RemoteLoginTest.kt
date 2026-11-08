package dev.vesta.cli

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RemoteLoginTest : FunSpec({
    test("주소는 https만, http는 이 기기와 Tailscale만") {
        RemoteLogin.normalize("https://Vesta.Example.com/").getOrThrow() shouldBe "https://vesta.example.com"
        RemoteLogin.normalize("vesta.example.com").getOrThrow() shouldBe "https://vesta.example.com"
        RemoteLogin.normalize("https://vesta.example.com:8443").getOrThrow() shouldBe "https://vesta.example.com:8443"
        RemoteLogin.normalize("http://127.0.0.1:8787").getOrThrow() shouldBe "http://127.0.0.1:8787"
        RemoteLogin.normalize("http://mac.tail1234.ts.net:8787").getOrThrow() shouldBe "http://mac.tail1234.ts.net:8787"
        RemoteLogin.normalize("http://vesta.example.com").isFailure shouldBe true
        RemoteLogin.normalize("http://192.168.0.10:8787").isFailure shouldBe true
        RemoteLogin.normalize("https://vesta.example.com/api").isFailure shouldBe true
        RemoteLogin.normalize("https://user:pw@vesta.example.com").isFailure shouldBe true
        RemoteLogin.normalize("ftp://vesta.example.com").isFailure shouldBe true
    }

    test("Set-Cookie에서 세션 쿠키만 꺼낸다") {
        val headers = listOf("other=1; Path=/", "vesta_session=abc_DEF-123; Max-Age=2592000; Path=/; HttpOnly; Secure; SameSite=Strict")
        RemoteLogin.sessionCookie(headers) shouldBe "abc_DEF-123"
        RemoteLogin.sessionCookie(listOf("other=1")) shouldBe null
        RemoteLogin.sessionCookie(listOf("vesta_session=; Max-Age=0")) shouldBe null
    }

    test("로그인 파일은 권한 600으로 저장하고 다시 읽는다") {
        val dir = Files.createTempDirectory("vesta-remote")
        val path = dir.resolve("home/remote.json")
        val login = RemoteLogin("https://vesta.example.com", "mom", "s3ss10n", "csrf")
        RemoteLogin.save(login, path)
        RemoteLogin.load(path) shouldBe login
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) shouldBe "rw-------"
        RemoteLogin.save(login.copy(session = "new"), path)
        RemoteLogin.load(path)?.session shouldBe "new"
        RemoteLogin.delete(path)
        RemoteLogin.load(path) shouldBe null
    }
})
