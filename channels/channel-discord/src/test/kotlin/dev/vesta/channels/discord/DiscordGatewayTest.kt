package dev.vesta.channels.discord

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.server.websocket.WebSockets as ServerWebSockets

private fun parse(frame: Frame) = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject

private fun JsonObject.op() = this["op"]!!.jsonPrimitive.int

class DiscordGatewayTest : FunSpec({
    test("Identify → 이벤트 → 재연결 요청이면 Resume으로 이어 받고, 토큰 거부(4004)면 멈춘다") {
        testApplication {
            install(ServerWebSockets)
            val first = mutableListOf<JsonObject>()
            var connections = 0
            routing {
                webSocket("/") {
                    connections++
                    send(Frame.Text("""{"op":10,"d":{"heartbeat_interval":100000}}"""))
                    val hello = parse(incoming.receive())
                    first += hello
                    if (connections == 1) {
                        send(Frame.Text("""{"op":0,"s":1,"t":"READY","d":{"session_id":"sess","resume_gateway_url":"ws://localhost","user":{"id":"1","username":"vesta"}}}"""))
                        send(Frame.Text("""{"op":0,"s":2,"t":"MESSAGE_CREATE","d":{"id":"m","channel_id":"dm","content":"hi"}}"""))
                        send(Frame.Text("""{"op":7,"d":null}"""))
                        for (frame in incoming) Unit
                    } else {
                        send(Frame.Text("""{"op":0,"s":3,"t":"RESUMED","d":{}}"""))
                        close(CloseReason(4004, "Authentication failed"))
                    }
                }
            }
            val client = createClient { install(WebSockets) }
            val events = mutableListOf<String>()
            withTimeout(15_000) {
                DiscordGateway("tok", client, gatewayUrl = { "ws://localhost" }).run { type, _ -> events += type }
            }
            events shouldBe listOf("READY", "MESSAGE_CREATE", "RESUMED")
            first[0].op() shouldBe 2
            first[0]["d"]!!.jsonObject["intents"]!!.jsonPrimitive.int shouldBe 4096
            first[0]["d"]!!.jsonObject["token"]!!.jsonPrimitive.content shouldBe "tok"
            first[1].op() shouldBe 6
            first[1]["d"]!!.jsonObject["session_id"]!!.jsonPrimitive.content shouldBe "sess"
            first[1]["d"]!!.jsonObject["seq"]!!.jsonPrimitive.int shouldBe 2
        }
    }

    test("heartbeat 응답이 없으면 끊고 다시 붙는다") {
        testApplication {
            install(ServerWebSockets)
            var connections = 0
            val heartbeats = mutableListOf<Int>()
            routing {
                webSocket("/") {
                    connections++
                    if (connections == 1) {
                        send(Frame.Text("""{"op":10,"d":{"heartbeat_interval":200}}"""))
                        // 응답(op 11)을 보내지 않는다
                        for (frame in incoming) parse(frame).op().takeIf { it == 1 }?.let { heartbeats += it }
                    } else {
                        close(CloseReason(4004, "Authentication failed"))
                    }
                }
            }
            val client = createClient { install(WebSockets) }
            withTimeout(15_000) {
                DiscordGateway("tok", client, gatewayUrl = { "ws://localhost" }).run { _, _ -> }
            }
            connections shouldBe 2
            heartbeats.size shouldBe 1
        }
    }
})
