package dev.vesta.channels.telegram

import dev.vesta.channels.ChannelFiles
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.channel.ChannelBinding
import dev.vesta.core.channel.ChannelBindingStore
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.tools.ApprovalDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path

/** 보낸·고친 메시지를 기록하는 가짜 Bot API */
class FakeTelegram : TelegramApi {
    data class Sent(val id: Long, var text: String, var html: Boolean, var keyboard: TgKeyboard?, val forceReply: Boolean)

    val messages = mutableListOf<Sent>()
    val callbackAnswers = mutableListOf<String?>()
    var commands: List<Pair<String, String>> = emptyList()
    var file: ByteArray = ByteArray(0)
    /** HTML로 보내면 이 오류를 낸다 (대체 경로 시험) */
    var rejectHtml = false

    override suspend fun getMe() = TgUser(1, isBot = true, username = "test_vesta_bot")
    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> = emptyList()

    override suspend fun sendMessage(chatId: Long, text: String, html: Boolean, keyboard: TgKeyboard?, forceReply: Boolean): TgMessage {
        if (html && rejectHtml) throw TelegramException(400, "Bad Request: can't parse entities")
        check(text.length <= 4096) { "too long: ${text.length}" }
        val sent = Sent(messages.size + 100L, text, html, keyboard, forceReply)
        messages += sent
        return TgMessage(sent.id, TgChat(chatId, "private"))
    }

    override suspend fun editMessage(chatId: Long, messageId: Long, text: String, html: Boolean, keyboard: TgKeyboard?) {
        if (html && rejectHtml) throw TelegramException(400, "Bad Request: can't parse entities")
        val sent = messages.first { it.id == messageId }
        if (sent.text == text && sent.keyboard == keyboard) throw TelegramException(400, "Bad Request: message is not modified")
        sent.text = text
        sent.html = html
        sent.keyboard = keyboard
    }

    override suspend fun answerCallback(callbackId: String, text: String?) { callbackAnswers += text }
    override suspend fun sendTyping(chatId: Long) = Unit
    override suspend fun setCommands(commands: List<Pair<String, String>>) { this.commands = commands }
    override suspend fun download(fileId: String, maxBytes: Long) = file
}

class MemoryBindings : ChannelBindingStore {
    val items = mutableMapOf<String, ChannelBinding>()
    var pairing: Pair<String, UserContext>? = null
    var users = mapOf<Long, UserContext>()

    override suspend fun find(channel: ChannelType, externalUserId: String) = items[externalUserId]
    override suspend fun bind(user: UserId, channel: ChannelType, externalUserId: String, externalChatId: String) {
        items[externalUserId] = ChannelBinding(users.getValue(user.value), channel, externalUserId, externalChatId, null)
    }
    override suspend fun setSession(channel: ChannelType, externalUserId: String, sessionId: SessionId?) {
        items[externalUserId] = items.getValue(externalUserId).copy(sessionId = sessionId)
    }
    override suspend fun list(channel: ChannelType) = items.values.toList()
    override suspend fun remove(channel: ChannelType, externalUserId: String) = items.remove(externalUserId) != null
    override suspend fun createPairing(user: UserId, channel: ChannelType, codeHash: String, ttl: Duration) {
        pairing = codeHash to users.getValue(user.value)
    }
    override suspend fun consumePairing(channel: ChannelType, codeHash: String): UserContext? =
        pairing?.takeIf { it.first == codeHash }?.second?.also { pairing = null }
    override suspend fun clearPairings(channel: ChannelType) { pairing = null }
}

/** 질문마다 [script]대로 이벤트를 내는 대화 엔진. 승인 요청은 [decisions]로 답을 받는다 */
class FakeConversation(
    private val script: suspend FlowCollector<AgentEvent>.(UserInput, FakeConversation) -> Unit,
) : ConversationService {
    val inputs = mutableListOf<String>()
    val sessions = mutableListOf<SessionId?>()
    val decisions = mutableMapOf<String, CompletableDeferred<ApprovalDecision>>()

    fun open(id: String) = CompletableDeferred<ApprovalDecision>().also { decisions[id] = it }

    override fun send(user: UserContext, channel: ChannelType, sessionId: SessionId?, input: UserInput): Flow<AgentEvent> = flow {
        inputs += input.text
        sessions += sessionId
        script(input, this@FakeConversation)
    }

    override suspend fun resolveApproval(user: UserContext, requestId: String, decision: ApprovalDecision): Boolean =
        decisions.remove(requestId)?.complete(decision) ?: false
}

class FakeFiles : ChannelFiles {
    val added = mutableListOf<Pair<String, Path>>()
    override fun supports(fileName: String) = fileName.endsWith(".md") || fileName.endsWith(".txt") || fileName.endsWith(".pdf")
    override suspend fun save(user: UserContext, channel: ChannelType, fileName: String, bytes: ByteArray): Path = Path("/inbox/$fileName")
    override suspend fun readText(path: Path, maxChars: Int) = "회의록: 다음 주 화요일에 배포"
    override suspend fun collections(user: UserContext) = listOf("notes")
    override suspend fun addToCollection(user: UserContext, collection: String, path: Path): String {
        added += collection to path
        return "색인 완료 (청크 3개)"
    }
}
