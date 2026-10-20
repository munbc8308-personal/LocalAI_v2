package dev.vesta.channels.discord

import kotlinx.serialization.json.JsonObject

/** 보낸·고친 메시지와 상호 작용 응답을 기록하는 가짜 REST API */
class FakeDiscord : DiscordApi {
    data class Sent(val id: String, val channelId: String, var content: String, var rows: List<List<DcButton>>)

    val messages = mutableListOf<Sent>()
    val responses = mutableListOf<JsonObject>()
    var file: ByteArray = ByteArray(0)
    val downloads = mutableListOf<String>()
    /** 메시지를 만든 뒤 응답을 돌려주기 전에 (그 사이 사용자가 누르는 경우 시험) */
    var onCreate: (suspend (Sent) -> Unit)? = null

    override suspend fun me() = DcUser("1", "vesta", bot = true)
    override suspend fun applicationId() = "1"
    override suspend fun gatewayUrl() = "wss://gateway.discord.gg"

    override suspend fun createMessage(channelId: String, content: String, rows: List<List<DcButton>>): DcMessage {
        check(content.length <= 2000) { "too long: ${content.length}" }
        check(rows.size <= 5 && rows.all { it.size <= 5 }) { "too many buttons" }
        val sent = Sent("m${messages.size + 1}", channelId, content, rows)
        messages += sent
        onCreate?.invoke(sent)
        return DcMessage(sent.id, channelId)
    }

    override suspend fun editMessage(channelId: String, messageId: String, content: String, rows: List<List<DcButton>>) {
        check(content.length <= 2000) { "too long: ${content.length}" }
        val sent = messages.first { it.id == messageId }
        sent.content = content
        sent.rows = rows
    }

    override suspend fun respond(interactionId: String, interactionToken: String, response: JsonObject) {
        responses += response
    }

    override suspend fun download(url: String, maxBytes: Long): ByteArray {
        downloads += url
        return file
    }
}
