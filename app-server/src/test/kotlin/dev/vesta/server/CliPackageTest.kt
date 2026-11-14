package dev.vesta.server

import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class CliPackageTest : FunSpec({
    val owner = UserContext(UserId(1), UserRole.OWNER)

    test("CLI 받기 (RC-06): 로그인해야 받고, 꾸러미가 없으면 404") { testApplication {
        val zip = createTempDirectory("vesta-cli").resolve("vesta-cli.zip")
        application { vestaApi(ServerDeps(SilentConversation, EmptySessions, EmptyMessages, owner, token = "secret", version = "9.9", cliPackage = zip)) }

        client.get("/api/cli").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/cli/download").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/cli") { bearerAuth("secret") }.status shouldBe HttpStatusCode.NotFound

        Files.write(zip, byteArrayOf(0x50, 0x4b, 3, 4, 9))
        client.get("/api/cli") { bearerAuth("secret") }.bodyAsText() shouldBe """{"version":"9.9","bytes":5}"""
        val response = client.get("/api/cli/download") { bearerAuth("secret") }
        response.status shouldBe HttpStatusCode.OK
        response.headers[HttpHeaders.ContentDisposition]!! shouldContain "vesta-cli-9.9.zip"
        response.readRawBytes().size shouldBe 5
    } }
})
