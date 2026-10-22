package dev.vesta.server

import dev.vesta.app.AppContainer
import dev.vesta.app.BotTokens
import dev.vesta.channels.ChannelActions
import dev.vesta.channels.ChannelFiles
import dev.vesta.channels.ChannelNotifier
import dev.vesta.channels.discord.DiscordBot
import dev.vesta.channels.discord.DiscordGateway
import dev.vesta.channels.discord.HttpDiscordApi
import dev.vesta.channels.slack.HttpSlackApi
import dev.vesta.channels.slack.SlackBot
import dev.vesta.channels.slack.SlackSocket
import dev.vesta.channels.telegram.HttpTelegramApi
import dev.vesta.channels.telegram.TelegramBot
import dev.vesta.core.ChannelType
import dev.vesta.core.ModelRole
import dev.vesta.core.UserContext
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.knowledge.DocumentParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

private val log = KotlinLogging.logger {}

/**
 * 메신저 봇을 띄운다 (PROJECT.md 6.2: 서버가 채널 어댑터를 기동). 토큰이 없으면 건너뛴다.
 * 띄운 봇의 알림 창구를 돌려준다 (주간 점검 알림, M16). [actions]는 알림 카드 버튼 처리
 */
fun startChannels(app: AppContainer, scope: CoroutineScope, actions: ChannelActions? = null, status: suspend () -> String): List<ChannelNotifier> {
    val channels = app.config.channels
    val files = ServerChannelFiles(app)
    val notifiers = mutableListOf<ChannelNotifier>()

    val telegram = channels.telegram
    val telegramToken = BotTokens.read("telegram")
    when {
        !telegram.enabled -> log.info { "Telegram 꺼짐 (channels.telegram.enabled: false)" }
        telegramToken == null -> log.info { "Telegram 토큰 없음 — 켜려면 vesta telegram token" }
        else -> {
            val bot = TelegramBot(
                HttpTelegramApi(telegramToken, baseUrl = telegram.apiUrl), app.conversation, app.channelBindings, files, telegram, status,
                speechToText = app.speechToText, textToSpeech = app.textToSpeech, mediaLimits = app.config.media, actions = actions,
            )
            notifiers += bot.notifier
            scope.launch { bot.run() }
        }
    }

    val discord = channels.discord
    val discordToken = BotTokens.read("discord")
    when {
        !discord.enabled -> log.info { "Discord 꺼짐 (channels.discord.enabled: false)" }
        discordToken == null -> log.info { "Discord 토큰 없음 — 켜려면 vesta discord token" }
        else -> {
            val api = HttpDiscordApi(discordToken, baseUrl = discord.apiUrl)
            // 서버 채널에서 부르면 받으려면 서버 글 이벤트도 받는다 (M18)
            val intents = DiscordGateway.DIRECT_MESSAGES or (if (discord.publicChannels) DiscordGateway.GUILD_MESSAGES else 0)
            val gateway = DiscordGateway(discordToken, HttpDiscordApi.gatewayClient(), gatewayUrl = { api.gatewayUrl() }, intents = intents)
            val bot = DiscordBot(
                api, gateway, app.conversation, app.channelBindings, files, discord, status,
                speechToText = app.speechToText, mediaLimits = app.config.media, actions = actions, textToSpeech = app.textToSpeech,
                publicGuest = if (discord.publicChannels) ({ publicGuest(app, "discord-guest") }) else null,
            )
            notifiers += bot.notifier
            scope.launch { bot.run() }
        }
    }

    val slack = channels.slack
    val slackBot = BotTokens.read("slack")
    val slackApp = BotTokens.read("slack", account = BotTokens.APP)
    when {
        !slack.enabled -> log.info { "Slack 꺼짐 (channels.slack.enabled: false)" }
        slackBot == null || slackApp == null -> log.info { "Slack 토큰 없음 — 켜려면 vesta slack token" }
        else -> {
            val api = HttpSlackApi(slackBot, slackApp, baseUrl = slack.apiUrl)
            val bot = SlackBot(
                api, SlackSocket(api, HttpSlackApi.socketClient()), app.conversation, app.channelBindings, files, slack, status,
                speechToText = app.speechToText, mediaLimits = app.config.media, actions = actions,
            )
            notifiers += bot.notifier
            scope.launch { bot.run() }
        }
    }
    return notifiers
}

/**
 * 공개 대화방 손님 계정 (GUEST, M18). 처음 부를 때 만든다. 사용자 수 제한(가족·팀 5명)에는 세지 않는 시스템 계정이라
 * UserService를 거치지 않고 저장소에 바로 만든다
 */
private suspend fun publicGuest(app: AppContainer, name: String): UserContext {
    val user = app.userRepository.find(name) ?: runCatching { app.userRepository.create(name, dev.vesta.core.UserRole.GUEST, null) }
        .getOrElse { app.userRepository.find(name) ?: throw it }
    return UserContext(user.id, dev.vesta.core.UserRole.GUEST)
}

/** 메신저로 받은 파일: `~/.vesta/inbox/<채널>/`에 저장, 본문 읽기, 문서 컬렉션 색인 */
class ServerChannelFiles(private val app: AppContainer) : ChannelFiles {
    override fun supports(fileName: String) = DocumentParser.supports(Path(fileName))

    override suspend fun save(user: UserContext, channel: ChannelType, fileName: String, bytes: ByteArray): Path = withContext(Dispatchers.IO) {
        val dir = app.paths.home.resolve("inbox").resolve(channel.name.lowercase()).createDirectories()
        val safe = fileName.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(80).ifEmpty { "file" }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        dir.resolve("$stamp-$safe").also { it.writeBytes(bytes) }
    }

    override suspend fun readText(path: Path, maxChars: Int): String = withContext(Dispatchers.IO) {
        val text = DocumentParser.parse(path).blocks.joinToString("\n\n") { it.text }
        if (text.length > maxChars) text.take(maxChars) + "\n…(이하 생략)" else text
    }

    override suspend fun collections(user: UserContext): List<String> =
        app.knowledge.listCollections(user.userId).filter { it.kind == CollectionKind.DOCS }.map { it.name }

    override suspend fun addToCollection(user: UserContext, collection: String, path: Path): String {
        val target = app.knowledge.findCollection(user.userId, collection) ?: return "컬렉션이 없습니다"
        if (target.kind != CollectionKind.DOCS) return "문서 컬렉션이 아닙니다"
        if (app.config.models.embedding != null) app.modelManager.acquire(ModelRole.EMBEDDING)
        val summary = app.indexer.index(target, listOf(path))
        return when {
            summary.indexed > 0 -> "색인 완료 (청크 ${summary.chunks}개)"
            summary.unchanged > 0 -> "이미 같은 내용이 있습니다"
            else -> "색인하지 못했습니다 (내용이 없거나 읽을 수 없는 파일)"
        }
    }
}
