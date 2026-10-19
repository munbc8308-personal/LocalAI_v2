package dev.vesta.app

import java.util.concurrent.TimeUnit

/**
 * 메신저 봇 토큰 (PROJECT.md 6.6: 키체인 또는 환경 변수, 설정 파일에 평문으로 두지 않음).
 * 키체인 서비스 `dev.vesta.<채널>`, 계정 `bot-token`. 저장할 때는 토큰이 프로세스 인자(ps)에 보이지 않게
 * `security -i`의 표준 입력으로 넘긴다.
 */
object BotTokens {
    private const val ACCOUNT = "bot-token"

    /** 환경 변수 `VESTA_<채널>_TOKEN`이 있으면 그것을 쓴다 */
    fun read(channel: String, env: Map<String, String> = System.getenv()): String? =
        env["VESTA_${channel.uppercase()}_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: run(listOf("/usr/bin/security", "find-generic-password", "-s", service(channel), "-a", ACCOUNT, "-w"))?.trim()?.takeIf { it.isNotEmpty() }

    /** [token]은 호출하는 쪽에서 형식을 확인한다 (공백·따옴표·줄바꿈 없음) */
    fun write(channel: String, token: String): Boolean {
        require(token.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }) { "토큰 형식이 아닙니다" }
        return run(listOf("/usr/bin/security", "-i"), stdin = "add-generic-password -U -s ${service(channel)} -a $ACCOUNT -w $token\n") != null &&
            read(channel, env = emptyMap()) == token
    }

    fun delete(channel: String): Boolean =
        run(listOf("/usr/bin/security", "delete-generic-password", "-s", service(channel), "-a", ACCOUNT)) != null

    private fun service(channel: String) = "dev.vesta.${channel.lowercase()}"

    private fun run(command: List<String>, stdin: String? = null): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) null else output
    }.getOrNull()
}
