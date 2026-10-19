package dev.vesta.server

import dev.vesta.app.AppContainer
import dev.vesta.app.BotTokens
import dev.vesta.channels.ChannelFiles
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

/** 메신저 봇을 띄운다 (PROJECT.md 6.2: 서버가 채널 어댑터를 기동). 토큰이 없으면 건너뛴다 */
fun startChannels(app: AppContainer, scope: CoroutineScope, status: suspend () -> String) {
    val telegram = app.config.channels.telegram
    val token = BotTokens.read("telegram")
    when {
        !telegram.enabled -> log.info { "Telegram 꺼짐 (channels.telegram.enabled: false)" }
        token == null -> log.info { "Telegram 토큰 없음 — 켜려면 vesta telegram token" }
        else -> {
            val bot = TelegramBot(HttpTelegramApi(token, baseUrl = telegram.apiUrl), app.conversation, app.channelBindings, ServerChannelFiles(app), telegram, status)
            scope.launch { bot.run() }
        }
    }
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
