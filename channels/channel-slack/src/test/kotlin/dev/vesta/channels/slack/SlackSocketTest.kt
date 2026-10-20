package dev.vesta.channels.slack

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class SlackSocketTest : FunSpec({
    test("봉투마다 바로 확인을 보내고, 다시 보낸 이벤트는 한 번만 처리한다. disconnect면 다시 붙고 토큰 거부면 멈춘다") {
        testApplication {
            install(ServerWebSockets)
            val acks = mutableListOf<String>()
            var connections = 0
            routing {
                webSocket("/socket") {
                    connections++
                    send(Frame.Text("""{"type":"hello","num_connections":1}"""))
                    if (connections == 1) {
                        val envelope = """{"type":"events_api","envelope_id":"e1","payload":{"event_id":"Ev1","event":{"type":"message"}}}"""
                        send(Frame.Text(envelope))
                        acks += (incoming.receive() as Frame.Text).readText()
                        // 확인이 늦었다고 보고 같은 이벤트를 다시 보낸다
                        send(Frame.Text(envelope.replace("e1", "e2")))
                        acks += (incoming.receive() as Frame.Text).readText()
                        send(Frame.Text("""{"type":"interactive","envelope_id":"e3","payload":{"type":"block_actions"}}"""))
                        acks += (incoming.receive() as Frame.Text).readText()
                    }
                    send(Frame.Text("""{"type":"disconnect","reason":"refresh_requested"}"""))
                    for (frame in incoming) Unit
                }
            }
            val client = createClient { install(WebSockets) }
            val api = FakeSlack().apply {
                socketUrl = { if (connections < 2) "ws://localhost/socket" else throw SlackException(200, "invalid_auth") }
            }
            val received = mutableListOf<String>()
            withTimeout(15_000) {
                SlackSocket(api, client).run { type, _ -> received += type }
            }
            acks shouldBe listOf("""{"envelope_id":"e1"}""", """{"envelope_id":"e2"}""", """{"envelope_id":"e3"}""")
            received shouldBe listOf("events_api", "interactive")
            connections shouldBe 2
        }
    }
})
