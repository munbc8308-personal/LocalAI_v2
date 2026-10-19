package dev.vesta.app

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * 로컬 API 토큰 (PROJECT.md 9장: 비밀은 macOS 키체인). 서비스 `dev.vesta`, 계정 `api-token`.
 * 서버가 처음 뜰 때 만들고, CLI·외부 클라이언트는 같은 토큰으로 붙는다.
 * 확인: `security find-generic-password -s dev.vesta -a api-token -w`
 */
object ApiToken {
    private const val SERVICE = "dev.vesta"
    private const val ACCOUNT = "api-token"

    fun read(): String? = security("find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w")?.trim()?.takeIf { it.isNotEmpty() }

    fun readOrCreate(): String = read() ?: run {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = "vst_" + bytes.joinToString("") { "%02x".format(it) }
        check(security("add-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w", token, "-U") != null) { "키체인에 API 토큰을 저장하지 못했습니다" }
        log.info { "API 토큰을 새로 만들어 키체인에 저장 (서비스 $SERVICE)" }
        token
    }

    private fun security(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("/usr/bin/security") + args).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) null else out
    }.getOrNull()
}
