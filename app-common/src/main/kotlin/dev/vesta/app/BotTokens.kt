package dev.vesta.app

import java.util.concurrent.TimeUnit

/**
 * 메신저 봇 토큰 (PROJECT.md 6.6: 키체인 또는 환경 변수, 설정 파일에 평문으로 두지 않음).
 * 키체인 서비스 `dev.vesta.<채널>`, 계정 `bot-token`(Slack은 앱 토큰 `app-token`도). 저장할 때는 토큰이
 * 프로세스 인자(ps)에 보이지 않게 `security -i`의 표준 입력으로 넘긴다.
 */
object BotTokens {
    const val BOT = "bot-token"
    /** Slack Socket Mode 앱 토큰 (xapp-) */
    const val APP = "app-token"

    /** 환경 변수 `VESTA_<채널>_TOKEN`(앱 토큰은 `VESTA_<채널>_APP_TOKEN`)이 있으면 그것을 쓴다 */
    fun read(channel: String, env: Map<String, String> = System.getenv(), account: String = BOT): String? =
        env[envName(channel, account)]?.trim()?.takeIf { it.isNotEmpty() }
            ?: run(listOf("/usr/bin/security", "find-generic-password", "-s", service(channel), "-a", account, "-w"))?.trim()?.takeIf { it.isNotEmpty() }

    /** [token]은 호출하는 쪽에서 형식을 확인한다 (공백·따옴표·줄바꿈 없음) */
    fun write(channel: String, token: String, account: String = BOT): Boolean {
        require(token.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }) { "토큰 형식이 아닙니다" }
        return run(listOf("/usr/bin/security", "-i"), stdin = "add-generic-password -U -s ${service(channel)} -a $account -w $token\n") != null &&
            read(channel, env = emptyMap(), account = account) == token
    }

    fun delete(channel: String, account: String = BOT): Boolean =
        run(listOf("/usr/bin/security", "delete-generic-password", "-s", service(channel), "-a", account)) != null

    /** 키체인 비밀 하나 (A2A 토큰 등: 서비스 `dev.vesta.a2a`, 계정 = 에이전트 이름). 없으면 null */
    fun keychain(service: String, account: String): String? =
        run(listOf("/usr/bin/security", "find-generic-password", "-s", service, "-a", account, "-w"))?.trim()?.takeIf { it.isNotEmpty() }

    private fun envName(channel: String, account: String) =
        "VESTA_${channel.uppercase()}_" + if (account == APP) "APP_TOKEN" else "TOKEN"

    private fun service(channel: String) = "dev.vesta.${channel.lowercase()}"

    private fun run(command: List<String>, stdin: String? = null): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) null else output
    }.getOrNull()
}
