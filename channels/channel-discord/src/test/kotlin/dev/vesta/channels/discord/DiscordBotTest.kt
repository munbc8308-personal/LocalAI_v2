package dev.vesta.channels.discord

import dev.vesta.channels.PairingCode
import dev.vesta.channels.testing.FakeConversation
import dev.vesta.channels.testing.FakeFiles
import dev.vesta.channels.testing.MemoryBindings
import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.RiskLevel
import dev.vesta.core.SourceType
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.channel.ChannelBinding
import dev.vesta.core.config.DiscordConfig
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val owner = UserContext(UserId(1), UserRole.OWNER)
private const val ME = "42"
private const val DM = "dm42"

private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

private fun message(content: String, from: String = ME, channel: String = DM, guild: String? = null, bot: Boolean = false, attachment: String = "") = obj(
    """{"id":"in1","channel_id":"$channel",${guild?.let { "\"guild_id\":\"$it\"," } ?: ""}"author":{"id":"$from","username":"u$from","bot":$bot},
       "content":${JsonPrimitive(content)},"attachments":[$attachment]}""",
)

private fun button(customId: String, from: String = ME, channel: String = DM) = obj(
    """{"id":"i1","token":"t1","type":3,"channel_id":"$channel","user":{"id":"$from","username":"u$from"},"data":{"custom_id":"$customId"}}""",
)

private fun modal(customId: String, value: String, from: String = ME) = obj(
    """{"id":"i2","token":"t2","type":5,"channel_id":"$DM","user":{"id":"$from"},
       "data":{"custom_id":"$customId","components":[{"type":1,"components":[{"type":4,"custom_id":"command","value":"$value"}]}]}}""",
)

private class Harness(
    script: suspend FlowCollector<AgentEvent>.(UserInput, FakeConversation) -> Unit,
    config: DiscordConfig = DiscordConfig(streamIntervalMillis = 0),
    bound: Boolean = true,
) {
    val api = FakeDiscord()
    val bindings = MemoryBindings().apply {
        users = mapOf(1L to owner)
        if (bound) items[ME] = ChannelBinding(owner, ChannelType.DISCORD, ME, DM, null)
    }
    val conversation = FakeConversation(script)
    val files = FakeFiles()
    val stt = dev.vesta.channels.testing.FakeSpeechToText()
    val bot = DiscordBot(api, { }, conversation, bindings, files, config, nowMillis = { 0L }, speechToText = stt)

    suspend fun TestScope.send(type: String, data: JsonObject) {
        bot.dispatch(type, data, this)
        advanceUntilIdle()
    }
}

class DiscordBotTest : FunSpec({
    test("연결되지 않은 계정은 답하지 않고, 코드만 보내도 연결된다. 서버 대화방·봇 글은 받지 않는다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("hi")) }, bound = false)
            with(h) {
                send("MESSAGE_CREATE", message("안녕"))
                api.messages.shouldBeEmpty()

                bindings.createPairing(owner.userId, ChannelType.DISCORD, PairingCode.hash("ABCD2345"), java.time.Duration.ofMinutes(10))
                send("MESSAGE_CREATE", message("abcd-2345"))
                bindings.find(ChannelType.DISCORD, ME)?.externalChatId shouldBe DM
                api.messages.last().content shouldContain "연결됐습니다"
                api.messages.last().content shouldContain "!web"

                val before = api.messages.size
                send("MESSAGE_CREATE", message("질문", guild = "g1", channel = "c1"))
                send("MESSAGE_CREATE", message("질문", from = "1", bot = true))
                api.messages.size shouldBe before
                conversation.inputs.shouldBeEmpty()
            }
        }
    }

    test("답변: 표는 코드 블록으로, 인용한 출처만 붙이고 링크 미리보기 없이. ! 명령도 받는다") {
        runTest {
            val h = Harness({ _, _ ->
                emit(AgentEvent.Sources(listOf(
                    Evidence(SourceType.WEB, "https://example.com/a", "예시 [글]", null, "", 1.0, citation = 1),
                    Evidence(SourceType.LOCAL_DOCS, "/Users/me/secret/plan.md", "plan", "3–9행", "", 0.5, citation = 2),
                )))
                emit(AgentEvent.Token("| 이름 | 값 |\n|---|---|\n| 가 | 1 |\n\n근거 [1], [2] @everyone"))
                emit(AgentEvent.Done(AgentType.WEB, "m", null))
            })
            with(h) {
                send("MESSAGE_CREATE", message("!web 질문"))
                conversation.inputs.single() shouldBe "/web 질문"
                val reply = api.messages.single().content
                reply shouldContain "```\n이름 | 값\n-----+---\n가   | 1\n```"
                reply shouldContain "[1] [예시 (글)](<https://example.com/a>)"
                reply shouldContain "[2] plan.md · 3–9행"
                reply shouldNotContain "/Users/me/secret"
                reply shouldContain "*웹 리서치*"

                send("MESSAGE_CREATE", message("!new"))
                api.messages.last().content shouldContain "새 대화"
            }
        }
    }

    test("긴 답변은 2000자 안으로 나눈다") {
        runTest {
            val h = Harness({ _, _ ->
                repeat(20) { emit(AgentEvent.Token("문단 $it " + "가".repeat(300) + "\n\n")) }
                emit(AgentEvent.Done(AgentType.CHAT, "m", null))
            })
            with(h) {
                send("MESSAGE_CREATE", message("길게"))
                (api.messages.size > 3) shouldBe true
                api.messages.last().content shouldContain "문단 19"
            }
        }
    }

    test("셸 승인: 다른 사람 버튼은 본인에게만 보이는 안내로 막고, 수정은 입력 창으로 받는다") {
        runTest {
            val h = Harness({ _, c ->
                val decision = c.open("r1")
                emit(AgentEvent.ApprovalRequested("r1", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", "ls -la ```\n작업 폴더: ~/Documents"), "목록 확인", "command", "ls -la"))
                when (val d = decision.await()) {
                    is ApprovalDecision.ApproveEdited -> emit(AgentEvent.Token("실행: " + d.editedArguments["command"]!!.jsonPrimitive.content))
                    else -> emit(AgentEvent.Token("결과: $d"))
                }
                emit(AgentEvent.Done(AgentType.TASK, "m", null))
            })
            with(h) {
                send("MESSAGE_CREATE", message("파일 목록"))
                val card = api.messages.first { it.rows.isNotEmpty() }
                card.content shouldContain "```\nls -la `​``\n작업 폴더: ~/Documents\n```"
                card.rows.single().map { it.label } shouldBe listOf("승인", "수정", "거부")
                card.rows.single().map { it.style } shouldBe listOf(1, 2, 4)

                send("INTERACTION_CREATE", button("ap:r1:a", from = "99", channel = "dm99"))
                conversation.decisions.containsKey("r1") shouldBe true
                api.responses.last().let {
                    it["type"]!!.jsonPrimitive.int shouldBe 4
                    it["data"]!!.jsonObject["flags"]!!.jsonPrimitive.int shouldBe 64
                    it["data"]!!.jsonObject["content"]!!.jsonPrimitive.content shouldBe "권한이 없습니다"
                }

                send("INTERACTION_CREATE", button("ap:r1:e"))
                api.responses.last().let {
                    it["type"]!!.jsonPrimitive.int shouldBe 9
                    val input = it["data"]!!.jsonObject["components"]!!.jsonArray[0].jsonObject["components"]!!.jsonArray[0].jsonObject
                    input["value"]!!.jsonPrimitive.content shouldBe "ls -la"
                    it["data"]!!.jsonObject["custom_id"]!!.jsonPrimitive.content shouldBe "ed:r1"
                }

                // 다른 사람이 입력 창 결과를 보내도 무시
                send("INTERACTION_CREATE", modal("ed:r1", "rm -rf ~", from = "99"))
                conversation.decisions.containsKey("r1") shouldBe true

                send("INTERACTION_CREATE", modal("ed:r1", "ls -l"))
                api.responses.last()["type"]!!.jsonPrimitive.int shouldBe 6
                card.rows.shouldBeEmpty()
                card.content shouldContain "수정해서 승인함"
                card.content shouldContain "```\nls -l\n```"
                api.messages.last().content shouldContain "실행: ls -l"
            }
        }
    }

    test("승인 버튼은 응답만 하고 결과는 카드에 적는다. 끝난 요청은 안내한다") {
        runTest {
            val h = Harness({ _, c ->
                val decision = c.open("r2")
                emit(AgentEvent.ApprovalRequested("r2", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", "date"), null))
                emit(AgentEvent.Token("결정: ${decision.await()::class.simpleName}"))
                emit(AgentEvent.Done(AgentType.TASK, "m", null))
            })
            with(h) {
                send("MESSAGE_CREATE", message("날짜"))
                send("INTERACTION_CREATE", button("ap:r2:a"))
                api.responses.last()["type"]!!.jsonPrimitive.int shouldBe 6
                api.messages.first { it.content.contains("승인 필요") }.content shouldContain "→ 승인함"
                api.messages.last().content shouldContain "결정: Approve"

                send("INTERACTION_CREATE", button("ap:r2:a"))
                api.responses.last()["data"]!!.jsonObject["content"]!!.jsonPrimitive.content shouldBe "이미 끝난 요청입니다"
            }
        }
    }

    test("카드를 보내는 사이에 누른 버튼도 받고, 카드가 오면 결과를 적는다") {
        runTest {
            val h = Harness({ _, c ->
                val decision = c.open("r4")
                emit(AgentEvent.ApprovalRequested("r4", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", "date"), null))
                emit(AgentEvent.Token("결정: ${decision.await()::class.simpleName}"))
                emit(AgentEvent.Done(AgentType.TASK, "m", null))
            })
            with(h) {
                api.onCreate = { sent ->
                    if (sent.rows.isNotEmpty()) {
                        api.onCreate = null
                        bot.dispatch("INTERACTION_CREATE", button("ap:r4:a"), this@runTest)
                    }
                }
                send("MESSAGE_CREATE", message("날짜"))
                val card = api.messages.first { it.content.contains("승인 필요") }
                card.content shouldContain "→ 승인함"
                card.rows.shouldBeEmpty()
                api.messages.last().content shouldContain "결정: Approve"
            }
        }
    }

    test("긴 셸 명령은 Discord에 다 보여 줄 수 없어 거부한다") {
        runTest {
            val h = Harness({ _, c ->
                val decision = c.open("r3")
                emit(AgentEvent.ApprovalRequested("r3", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", "echo " + "a".repeat(1500)), null))
                emit(AgentEvent.Token("결정: ${decision.await()::class.simpleName}"))
                emit(AgentEvent.Done(AgentType.TASK, "m", null))
            })
            with(h) {
                send("MESSAGE_CREATE", message("긴 명령"))
                api.messages.none { it.rows.isNotEmpty() } shouldBe true
                api.messages.any { it.content.contains("너무 길어") } shouldBe true
                api.messages.last().content shouldContain "결정: Deny"
            }
        }
    }

    test("첨부 파일: 버튼은 한 줄 5개까지 나눠 담고, 고르면 질문하거나 컬렉션에 넣는다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("화요일입니다")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) })
            with(h) {
                val attachment = """{"id":"a1","filename":"meeting.md","size":100,"url":"https://cdn.discordapp.com/attachments/1/2/meeting.md"}"""
                send("MESSAGE_CREATE", message("", attachment = attachment))
                api.downloads.single() shouldBe "https://cdn.discordapp.com/attachments/1/2/meeting.md"
                val offer = api.messages.last()
                offer.rows.flatten().map { it.label } shouldBe listOf("이 파일에 대해 질문", "'notes' 컬렉션에 추가")
                send("INTERACTION_CREATE", button(offer.rows.flatten()[0].customId))
                offer.rows.shouldBeEmpty()
                send("MESSAGE_CREATE", message("배포일은?"))
                conversation.inputs.single() shouldContain "회의록: 다음 주 화요일에 배포"

                send("MESSAGE_CREATE", message("", attachment = attachment))
                send("INTERACTION_CREATE", button(api.messages.last().rows.flatten()[1].customId))
                files.added.single().first shouldBe "notes"
                api.messages.last().content shouldContain "색인 완료"
            }
        }
    }

    test("음성 메시지(audio/ogg 첨부)는 인식해서 묻고, 이미지 첨부는 비전 질문으로") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("3시")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) })
            with(h) {
                api.file = "OggS".toByteArray()
                send("MESSAGE_CREATE", message("", attachment = """{"id":"a1","filename":"voice-message.ogg","size":4,"url":"https://cdn.discordapp.com/v.ogg","content_type":"audio/ogg"}"""))
                api.messages.first().content shouldContain "내일 회의 몇 시야"
                conversation.inputs.single() shouldBe "내일 회의 몇 시야"

                api.file = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0)
                send("MESSAGE_CREATE", message("!web 이거 뭐야", attachment = """{"id":"a2","filename":"shot.png","size":5,"url":"https://cdn.discordapp.com/s.png","content_type":"image/png"}"""))
                conversation.userInputs.last().let {
                    it.text shouldBe "/web 이거 뭐야"
                    it.images.single().mime shouldBe "image/png"
                }
            }
        }
    }

})
