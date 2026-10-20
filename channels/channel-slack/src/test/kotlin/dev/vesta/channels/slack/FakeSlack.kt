package dev.vesta.channels.slack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 보낸·고친 메시지를 기록하는 가짜 Web API */
class FakeSlack : SlackApi {
    data class Sent(val ts: String, val channel: String, var text: String, var blocks: JsonArray?)

    val messages = mutableListOf<Sent>()
    val ephemeral = mutableListOf<Pair<String, String>>()
    val views = mutableListOf<JsonObject>()
    var file: ByteArray = ByteArray(0)
    val downloads = mutableListOf<String>()
    var socketUrl: () -> String = { "ws://localhost/socket" }

    override suspend fun authTest() = SlackAuth("UBOT", "vesta", "home")
    override suspend fun openConnection() = socketUrl()

    override suspend fun postMessage(channel: String, text: String, blocks: JsonArray?): String {
        val sent = Sent("17000000${messages.size}.0001", channel, text, blocks)
        messages += sent
        return sent.ts
    }

    override suspend fun update(channel: String, ts: String, text: String, blocks: JsonArray?) {
        val sent = messages.first { it.ts == ts }
        sent.text = text
        if (blocks != null) sent.blocks = blocks
    }

    override suspend fun postEphemeral(channel: String, user: String, text: String) { ephemeral += user to text }
    override suspend fun openView(triggerId: String, view: JsonObject) { views += view }

    val uploads = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun uploadFile(channel: String, fileName: String, bytes: ByteArray, title: String?) { uploads += fileName to bytes }

    override suspend fun download(url: String, maxBytes: Long): ByteArray {
        downloads += url
        return file
    }
}
