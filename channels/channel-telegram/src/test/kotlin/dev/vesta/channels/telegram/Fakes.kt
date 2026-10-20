package dev.vesta.channels.telegram

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
