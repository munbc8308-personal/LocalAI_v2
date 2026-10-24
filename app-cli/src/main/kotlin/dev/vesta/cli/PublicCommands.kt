package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.vesta.app.BotTokens
import dev.vesta.core.UserRole
import dev.vesta.core.config.PublicAccessConfig
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 지인 공개 (PROJECT.md 7.20, M26). 두 가지 방법:
 *
 * 직접 (공유기 포트포워딩 + DDNS, 예: iptime):
 * 1. `vesta public setup --direct --hostname 이름.iptime.org` — 설정 저장, 서버 다시 시작
 * 2. `scripts/direct-https.sh 이름.iptime.org` — Caddy(HTTPS, Let's Encrypt)·launchd 등록
 * 3. 공유기: 바깥 443·80 → 이 Mac 443·80 (8787은 열지 않는다)
 * 4. `vesta public invite <이름>` — 지인 계정 + 임시 비밀번호
 *
 * Cloudflare (도메인이 Cloudflare에 있을 때, 공유기 설정 없음). 순서:
 * 1. `vesta public token` — Cloudflare API 토큰을 키체인에 (Access 앱·허용 목록을 Vesta가 맞춘다)
 * 2. `vesta public setup --hostname vesta.내도메인 --email 내이메일` — Access 앱 만들기, 설정 저장, 서버 다시 시작
 * 3. `scripts/cloudflare-tunnel.sh vesta.내도메인` — 터널 만들기·launchd 등록
 * 4. `vesta public invite <이름> <이메일>` — 지인 계정 + 허용 목록
 */
class PublicCommand : CliktCommand(name = "public") {
    override fun help(context: Context) = "초대한 지인에게 공개 (Cloudflare Tunnel + Access)"
    override fun run() = Unit

    companion object {
        fun create() = PublicCommand().subcommands(PublicTokenCommand(), PublicSetupCommand(), PublicInviteCommand(), PublicSyncCommand(), PublicStatusCommand(), PublicOffCommand())
    }
}

class PublicTokenCommand : CliktCommand(name = "token") {
    override fun help(context: Context) = "Cloudflare API 토큰을 키체인에 저장 (권한: Access: Apps and Policies 편집, Access: Organizations… 읽기)"
    private val delete by option("--delete", help = "지운다").flag()

    override fun run() {
        if (delete) { BotTokens.delete(CloudflareAccess.KEYCHAIN, CloudflareAccess.ACCOUNT); echo("지웠습니다."); return }
        val console = System.console() ?: run { echo("터미널에서 실행하세요 (입력을 숨기려고 콘솔이 필요합니다)", err = true); throw ProgramResult(1) }
        echo("Cloudflare 대시보드 > 내 프로필 > API 토큰 > 토큰 만들기 > 사용자 지정 토큰")
        echo("  권한: 계정 · Access: Apps and Policies · 편집 / 계정 · Access: Organizations, Identity Providers, and Groups · 읽기")
        val token = console.readPassword("API 토큰 (입력이 보이지 않습니다): ")?.let(::String)?.trim().orEmpty()
        if (token.isEmpty()) { echo("취소했습니다."); return }
        val account = runCatching { CloudflareAccess(token).account(null) }.getOrElse {
            // 계정이 여럿이어도 토큰은 맞다
            if (it is CloudflareException && it.message?.startsWith("계정이 여럿") == true) null to "" else { echo("토큰 확인 실패: ${it.message}", err = true); throw ProgramResult(1) }
        }
        if (!BotTokens.write(CloudflareAccess.KEYCHAIN, token, CloudflareAccess.ACCOUNT)) { echo("키체인에 저장하지 못했습니다", err = true); throw ProgramResult(1) }
        echo("저장했습니다 (키체인 dev.vesta.cloudflare)" + (account.second.takeIf { it.isNotBlank() }?.let { ": 계정 $it" } ?: ""))
    }
}

class PublicSetupCommand : CliktCommand(name = "setup") {
    override fun help(context: Context) = "공개 주소 앞에 Access 로그인 관문을 만들고 설정을 저장한다 (서버 다시 시작)"
    private val hostname by option("--hostname", help = "바깥 주소, 예: vesta.example.com (Cloudflare에 있는 내 도메인)")
    private val email by option("--email", help = "Mac 주인의 로그인 이메일 (바깥에서 주인 계정으로 쓸 때)")
    private val account by option("--account", help = "Cloudflare 계정 이름 (여럿일 때)")
    private val team by option("--team", help = "API 토큰 없이: Access 팀 도메인 (<팀>.cloudflareaccess.com)")
    private val aud by option("--aud", help = "API 토큰 없이: Access 앱의 AUD 태그")
    private val ownerAdmin by option("--owner-admin", help = "바깥에서도 주인 관리 기능(사용자·설정·학습)을 쓴다").flag()
    private val direct by option("--direct", help = "Cloudflare 없이 공유기 포트포워딩(443) + DDNS 주소 (Caddy가 HTTPS)").flag()

    override fun run() = runBlocking {
        val store = UserStore()
        val current = store.config.publicAccess
        val host = (hostname ?: current.hostname).trim().lowercase()
        if (!HOSTNAME.matches(host)) { echo("--hostname 이 필요합니다 (예: vesta.example.com, 이름.iptime.org)", err = true); throw ProgramResult(1) }
        if (direct) {
            if (host.endsWith(".iptime.org")) {
                // iptime.org CAA는 iptime 계정(dns-01)만 인증서를 받게 막아 둔다 → HTTPS를 쓸 수 없다
                echo("*.iptime.org 는 HTTPS 인증서를 받을 수 없습니다 (iptime.org의 CAA 제한). 무료 DuckDNS를 쓰세요: duckdns.org 에서 이름을 만들고 scripts/duckdns.sh <이름>", err = true)
                throw ProgramResult(1)
            }
            writePublicConfig(store.paths.configFile, PublicAccessConfig(enabled = true, mode = "direct", port = current.port, hostname = host, ownerAdmin = ownerAdmin || current.ownerAdmin))
            echo("설정을 저장했습니다: ${store.paths.configFile} (publicAccess, 직접 모드)")
            restartServer()
            val short = store.service.list().filter { it.hasPassword && it.name !in dev.vesta.identity.UserService.SYSTEM_USERS }.map { it.name }
            echo("")
            echo("바깥 로그인은 ${PublicAccessConfig.DIRECT_MIN_PASSWORD}자 이상 비밀번호만 받습니다 (짧으면 vesta user passwd <이름>, 주인은 vesta passwd)")
            if (host.endsWith(".duckdns.org")) echo("공인 IP 갱신: 저장소의 scripts/duckdns.sh ${host.removeSuffix(".duckdns.org")}   (공유기는 DuckDNS를 갱신하지 못함)")
            echo("다음: 1) 저장소의 scripts/direct-https.sh $host   (HTTPS 인증서·Caddy)")
            echo("      2) 공유기 포트포워딩: 바깥 443 → 이 Mac(${lanAddress() ?: "이 Mac IP"}) 443, 바깥 80 → 80. 8787 포워딩은 지우세요")
            echo("      3) vesta public status")
            if (short.isEmpty()) echo("      (아직 비밀번호가 있는 사용자가 없습니다)")
            return@runBlocking
        }
        email?.let { e -> userAction { store.service.setEmail(store.service.list().first { it.role == UserRole.OWNER }.name, e) } }
        val (teamDomain, audience) = if (team != null && aud != null) {
            team!!.trim().removePrefix("https://").removeSuffix("/") to aud!!.trim()
        } else {
            val cf = cloudflare() ?: run {
                echo("API 토큰이 없습니다. vesta public token 으로 저장하거나, 대시보드에서 Access 앱을 만들고 --team · --aud 를 주세요", err = true)
                throw ProgramResult(1)
            }
            access {
                val (id, name) = cf.account(account)
                val domain = cf.teamDomain(id)
                val app = cf.findApp(id, host)?.also { echo("Access 앱이 이미 있습니다: $host") } ?: cf.createApp(id, host).also { echo("Access 앱을 만들었습니다: $host (계정 $name)") }
                val emails = emails(store)
                cf.syncEmails(id, app, emails)
                echo("허용 목록: ${emails.ifEmpty { listOf("(없음: 아무도 못 들어옵니다)") }.joinToString()}")
                domain to app.aud
            }
        }
        val next = PublicAccessConfig(enabled = true, mode = "cloudflare", port = current.port, hostname = host, teamDomain = teamDomain, audience = audience, ownerAdmin = ownerAdmin || current.ownerAdmin)
        writePublicConfig(store.paths.configFile, next)
        echo("설정을 저장했습니다: ${store.paths.configFile} (publicAccess)")
        restartServer()
        echo("")
        echo("다음: 터널 만들기 — 저장소의 scripts/cloudflare-tunnel.sh $host")
        echo("      지인 초대 — vesta public invite <이름> <이메일>")
        echo("      확인 — vesta public status")
    }
}

class PublicInviteCommand : CliktCommand(name = "invite") {
    override fun help(context: Context) = "지인을 초대한다: 계정(기본 손님) + 임시 비밀번호 + Access 허용 목록"
    private val name by argument()
    private val email by argument(help = "Cloudflare 방식일 때 지인의 로그인 이메일 (직접 방식은 필요 없음)").optional()
    private val role by option("--role", help = "guest: 대화·웹 검색만 / member: 문서·파일(자기 폴더)·이미지도").choice("guest", "member").default("guest")

    override fun run() = runBlocking {
        val store = UserStore()
        val direct = store.config.publicAccess.direct
        if (!direct && email == null) { echo("Cloudflare 방식은 이메일이 필요합니다: vesta public invite $name <이메일>", err = true); throw ProgramResult(1) }
        val password = temporaryPassword()
        userAction {
            store.service.create(name, UserRole.valueOf(role.uppercase()), password)
            email?.let { e -> runCatching { store.service.setEmail(name, e) }.onFailure { store.service.delete(name); throw it } }
        }
        val synced = direct || syncAccess(store)
        val host = store.config.publicAccess.hostname.ifBlank { "(vesta public setup 을 먼저)" }
        echo("초대했습니다: $name (${if (role == "guest") "손님: 대화·웹 검색" else "가족: 문서·파일·이미지"})")
        if (!synced) echo("! Access 허용 목록에는 직접 넣으세요: Zero Trust > Access > 애플리케이션 > Vesta > 정책 '${CloudflareAccess.POLICY}'에 $email")
        store.config.users.dailyQuestions.takeIf { it > 0 }?.let { echo("  하루 질문 한도: ${it}개 (users.dailyQuestions)") }
        echo("")
        echo("── 지인에게 보낼 내용 ──")
        echo("주소: https://$host")
        var step = 1
        if (!direct) echo("${step++}) 이메일 $email 을 넣고, 메일로 온 코드로 Cloudflare 로그인")
        echo("${step++}) Vesta 로그인: 이름 $name · 임시 비밀번호 $password")
        echo("$step) 로그인한 뒤 설정에서 비밀번호를 바꿔 주세요" + if (direct) " (${PublicAccessConfig.DIRECT_MIN_PASSWORD}자 이상)" else "")
        echo("────────────────────")
        echo("임시 비밀번호는 다시 보여 주지 않습니다. 잃어버리면 vesta user passwd $name")
    }
}

class PublicSyncCommand : CliktCommand(name = "sync") {
    override fun help(context: Context) = "Access 허용 목록을 Vesta 사용자 이메일과 맞춘다 (사용자를 지우거나 이메일을 바꾼 뒤)"

    override fun run() = runBlocking {
        val store = UserStore()
        if (!syncAccess(store)) { echo("API 토큰이나 공개 설정이 없어 맞추지 못했습니다 (vesta public token · setup)", err = true); throw ProgramResult(1) }
    }
}

class PublicStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "공개 상태: 설정·터널·관문이 막고 있는지 바깥에서 확인"

    override fun run() = runBlocking {
        val store = UserStore()
        val config = store.config.publicAccess
        val checks = publicChecks(config, store.config.server.port)
        checks.forEach { echo("${it.first} ${it.second}") }
        if (config.enabled) {
            echo("")
            echo("바깥 접속 사용자:")
            val usage = store.service.usageToday()
            store.service.list().filter { if (config.direct) it.hasPassword && it.name !in dev.vesta.identity.UserService.SYSTEM_USERS else it.email != null }.forEach { u ->
                echo("  ${u.name.padEnd(14)} ${u.role.name.padEnd(6)} ${u.email.orEmpty()}  오늘 질문 ${usage[u.id]?.questions ?: 0}개")
            }
            if (!config.direct) cloudflare()?.let { cf ->
                runCatching {
                    val id = cf.account(null).first
                    val app = cf.findApp(id, config.hostname) ?: return@runCatching echo("! Access 앱이 없습니다 (vesta public setup)")
                    val allowed = cf.emails(id, app).toSet()
                    val wanted = emails(store).toSet()
                    if (allowed == wanted) echo("✓ Access 허용 목록이 사용자 이메일과 같습니다 (${allowed.size}명)")
                    else echo("! Access 허용 목록이 다릅니다: 더할 것 ${wanted - allowed}, 뺄 것 ${allowed - wanted} → vesta public sync")
                }.onFailure { echo("· Access 목록 확인 실패: ${it.message}") }
            }
        }
        if (checks.any { it.first == "✗" }) throw ProgramResult(1)
    }
}

class PublicOffCommand : CliktCommand(name = "off") {
    override fun help(context: Context) = "공개를 끈다 (터널 입구를 닫는다. 터널·Access 앱은 남겨 둔다)"

    override fun run() {
        val store = UserStore()
        writePublicConfig(store.paths.configFile, store.config.publicAccess.copy(enabled = false))
        restartServer()
        echo("껐습니다. 터널 서비스도 내리려면: scripts/cloudflare-tunnel.sh off")
    }
}

private val HOSTNAME = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

private fun cloudflare(): CloudflareAccess? = BotTokens.read(CloudflareAccess.KEYCHAIN, account = CloudflareAccess.ACCOUNT)?.let(::CloudflareAccess)

private inline fun <T> CliktCommand.access(block: () -> T): T = try {
    block()
} catch (e: CloudflareException) {
    echo(e.message, err = true)
    throw ProgramResult(1)
}

/** 허용 목록 = 이메일이 있는 Vesta 사용자 */
private suspend fun emails(store: UserStore): List<String> = store.service.list().mapNotNull { it.email }.distinct()

/** API 토큰과 공개 설정이 있으면 Access 허용 목록을 맞춘다. 못 했으면 false */
internal suspend fun CliktCommand.syncAccess(store: UserStore): Boolean {
    val config = store.config.publicAccess
    val cf = cloudflare() ?: return false
    if (config.hostname.isBlank()) return false
    return runCatching {
        val id = cf.account(null).first
        val app = cf.findApp(id, config.hostname) ?: throw CloudflareException("Access 앱이 없습니다 (vesta public setup)")
        val list = emails(store)
        cf.syncEmails(id, app, list)
        echo("Access 허용 목록을 맞췄습니다: ${list.joinToString().ifBlank { "(없음)" }}")
    }.onFailure { echo("! Access 허용 목록을 맞추지 못했습니다: ${it.message}", err = true) }.isSuccess
}

/** 헷갈리는 글자(0·O·1·l·I)를 뺀 임시 비밀번호 (xxxx-xxxx-xxxx, 약 62비트) */
internal fun temporaryPassword(random: SecureRandom = SecureRandom()): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    return (1..3).joinToString("-") { (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
}

/** 설정 파일의 `publicAccess:` 묶음만 바꾼다 (다른 설정·주석은 그대로) */
internal fun writePublicConfig(file: Path, config: PublicAccessConfig) {
    val block = buildString {
        appendLine("publicAccess:")
        appendLine("  enabled: ${config.enabled}")
        appendLine("  mode: ${config.mode}")
        appendLine("  port: ${config.port}")
        appendLine("  hostname: \"${config.hostname}\"")
        appendLine("  teamDomain: \"${config.teamDomain}\"")
        appendLine("  audience: \"${config.audience}\"")
        appendLine("  ownerAdmin: ${config.ownerAdmin}")
    }
    val text = if (file.exists()) file.readText() else ""
    file.writeText(replaceTopLevelBlock(text, "publicAccess", block))
}

/** YAML 최상위 키 [key]의 묶음을 [block]으로 바꾼다. 없으면 끝에 붙인다. 다음 최상위 키 앞의 주석은 다음 키 것으로 본다 */
internal fun replaceTopLevelBlock(text: String, key: String, block: String): String {
    val lines = text.lines()
    val start = lines.indexOfFirst { it.startsWith("$key:") }
    if (start < 0) return text.trimEnd() + "\n\n" + block
    var end = (start + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && !lines[it].startsWith(" ") && !lines[it].startsWith("#") } ?: lines.size
    while (end - 1 > start && (lines[end - 1].isBlank() || lines[end - 1].startsWith("#"))) end--
    val after = lines.drop(end)
    return (lines.take(start) + block.trimEnd().lines() + after).joinToString("\n").let { if (after.isEmpty()) it + "\n" else it }
}

private fun restartServer() {
    val services = Services(listOf("dev.vesta.server"))
    if (!Files.exists(Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "dev.vesta.server.plist"))) return
    services.stop()
    Thread.sleep(1_000)
    services.start()
    println("서버를 다시 시작했습니다.")
}

/** (✓·!·✗, 글) 목록 */
internal fun publicChecks(config: PublicAccessConfig, serverPort: Int): List<Pair<String, String>> = buildList {
    if (!config.enabled) { add("·" to "지인 공개 꺼짐 (켜기: vesta public setup)"); return@buildList }
    if (!config.ready) { add("✗" to "설정이 덜 됐습니다: hostname·teamDomain·audience (vesta public setup)"); return@buildList }
    add("✓" to "설정: https://${config.hostname} · " + (if (config.direct) "직접(공유기 + Caddy)" else "Access ${config.teamDomain}") + if (config.ownerAdmin) " · 바깥 관리 허용" else "")
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun get(url: String): Pair<Int, String?>? = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() to response.headers().firstValue("location").orElse(null)
    }.getOrNull()
    // 터널 입구: Access 토큰 없이 오면 403이어야 한다 (Host는 java HttpClient가 바꿀 수 없어 127.0.0.1로 보내 Host 거부도 403)
    when (val local = get("http://127.0.0.1:${config.port}/api/health")) {
        null -> add("✗" to "바깥 입구(127.0.0.1:${config.port})가 닫혀 있습니다: 서버가 공개 설정을 읽었는지 (vesta public setup이 다시 시작함, 로그 ~/.vesta/logs/server.err.log)")
        else -> if (local.first == 403) add("✓" to "바깥 입구(127.0.0.1:${config.port})가 열려 있고, 공개 주소가 아닌 요청을 막습니다") else add("✗" to "바깥 입구가 공개 주소가 아닌 요청에 ${local.first}로 답합니다 (403이어야 함)")
    }
    if (config.direct) { addAll(directChecks(config, ::get)); return@buildList }
    val agent = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "dev.vesta.cloudflared.plist")
    add(if (agent.exists()) "✓" to "터널 서비스 등록됨 (dev.vesta.cloudflared)" else "✗" to "터널 서비스가 없습니다: scripts/cloudflare-tunnel.sh ${config.hostname}")
    // 바깥: 로그인하지 않은 요청은 Access 로그인 화면으로 넘겨져야 한다 (Vesta까지 오면 안 됨)
    when (val outside = get("https://${config.hostname}/api/health")) {
        null -> add("✗" to "https://${config.hostname} 에 연결하지 못했습니다 (DNS·터널 확인: cloudflared tunnel info vesta)")
        else -> when {
            outside.first in 300..399 && outside.second.orEmpty().contains("cloudflareaccess.com") -> add("✓" to "바깥 요청은 Access 로그인으로 넘어갑니다")
            outside.first == 200 -> add("✗" to "바깥에서 로그인 없이 Vesta에 닿습니다! Access 앱을 확인하세요 (Vesta 관문이 막고 있어야 하는데 200)")
            outside.first == 403 -> add("!" to "바깥 요청이 403입니다 (Access가 아니라 Vesta 관문이 막았을 수 있음: Access 앱의 도메인 확인)")
            outside.first == 530 || outside.first == 502 -> add("✗" to "Cloudflare가 터널에 닿지 못합니다 (HTTP ${outside.first}): launchctl print gui/$(id -u)/dev.vesta.cloudflared")
            else -> add("!" to "바깥 요청 응답 HTTP ${outside.first}")
        }
    }
    if (config.port == serverPort) add("✗" to "publicAccess.port가 server.port와 같습니다")
}

/** 직접 모드: Caddy가 돌고, 바깥 주소가 HTTPS로 열리고, 관문이 토큰 API를 막는지 */
private fun directChecks(config: PublicAccessConfig, get: (String) -> Pair<Int, String?>?): List<Pair<String, String>> = buildList {
    val agent = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "dev.vesta.caddy.plist")
    add(if (agent.exists()) "✓" to "HTTPS 서비스 등록됨 (dev.vesta.caddy)" else "✗" to "HTTPS 서비스가 없습니다: scripts/direct-https.sh ${config.hostname}")
    if (config.hostname.endsWith(".duckdns.org")) {
        val ddns = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "dev.vesta.duckdns.plist")
        val last = Path.of(System.getProperty("user.home"), ".vesta", "logs", "duckdns.log").takeIf { it.exists() }?.readText()?.lines()?.lastOrNull { it.isNotBlank() }
        when {
            !ddns.exists() -> add("!" to "DuckDNS 공인 IP 갱신이 없습니다: 집 IP가 바뀌면 끊깁니다 (scripts/duckdns.sh ${config.hostname.removeSuffix(".duckdns.org")})")
            last != null && "실패" in last -> add("!" to "DuckDNS 갱신 실패: $last")
            else -> add("✓" to "DuckDNS 공인 IP 갱신 중 (5분마다)")
        }
    }
    // 바깥 주소로 이 Mac에서 접속 (공유기가 같은 망 안의 요청도 포워딩해 주는 경우: iptime은 대부분 됨)
    when (val api = get("https://${config.hostname}/v1/models")) {
        null -> add("✗" to "https://${config.hostname} 에 연결하지 못했습니다: 공유기 포트포워딩(443 → 이 Mac 443)·인증서(~/.vesta/logs/caddy.err.log)를 확인하세요. 휴대폰 LTE로도 열어 보세요")
        else -> when (api.first) {
            404 -> add("✓" to "바깥 주소가 HTTPS로 열리고, 토큰 API(/v1)는 막혀 있습니다")
            502, 503, 504 -> add("✗" to "Caddy는 열렸지만 Vesta가 응답하지 않습니다 (HTTP ${api.first}): vesta status")
            else -> add("✗" to "바깥 /v1 이 ${api.first}입니다 (404여야 함): 8787을 바로 포워딩하지 않았는지 확인")
        }
    }
    when (val plain = get("http://${config.hostname}:8787/api/health")) {
        null -> add("✓" to "8787은 바깥에서 닫혀 있습니다")
        else -> add("✗" to "8787이 바깥에서 열립니다 (HTTP ${plain.first}): 공유기의 8787 포워딩을 지우세요")
    }
}

/** 이 Mac의 공유기 쪽 주소 (en0 → en1) */
private fun lanAddress(): String? = listOf("en0", "en1").firstNotNullOfOrNull { iface ->
    runCatching {
        val p = ProcessBuilder("/usr/sbin/ipconfig", "getifaddr", iface).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
    }.getOrNull()
}
