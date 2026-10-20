package dev.vesta.channels.slack

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
import dev.vesta.core.config.SlackConfig
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val owner = UserContext(UserId(1), UserRole.OWNER)
private const val ME = "U42"
private const val DM = "D42"

private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

private fun message(text: String, from: String = ME, channel: String = DM, channelType: String = "im", extra: String = "") = obj(
    """{"event":{"type":"message","user":"$from","channel":"$channel","channel_type":"$channelType","text":${JsonPrimitive(text)},"ts":"${System.currentTimeMillis() / 1000}.000100"$extra}}""",
)

private fun action(value: String, from: String = ME, channel: String = DM) = obj(
    """{"type":"block_actions","user":{"id":"$from","username":"u"},"channel":{"id":"$channel"},"trigger_id":"trig","actions":[{"action_id":"$value","value":"$value"}]}""",
)

private fun submission(requestId: String, value: String, from: String = ME) = obj(
    """{"type":"view_submission","user":{"id":"$from"},"view":{"callback_id":"vesta_edit","private_metadata":"$requestId",
       "state":{"values":{"cmd":{"v":{"type":"plain_text_input","value":${JsonPrimitive(value)}}}}}}}""",
)

/** 블록들의 글을 이어 붙인다 */
private fun FakeSlack.Sent.blockText() = blocks.orEmpty().joinToString("\n") { b ->
    b.jsonObject["text"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
}

private fun FakeSlack.Sent.buttons() = blocks.orEmpty().filter { it.jsonObject["type"]!!.jsonPrimitive.content == "actions" }
    .flatMap { it.jsonObject["elements"]!!.jsonArray }.map { it.jsonObject }

private class Harness(
    script: suspend FlowCollector<AgentEvent>.(UserInput, FakeConversation) -> Unit,
    config: SlackConfig = SlackConfig(streamIntervalMillis = 0),
    bound: Boolean = true,
) {
    val api = FakeSlack()
    val bindings = MemoryBindings().apply {
        users = mapOf(1L to owner)
        if (bound) items[ME] = ChannelBinding(owner, ChannelType.SLACK, ME, DM, null)
    }
    val conversation = FakeConversation(script)
    val files = FakeFiles()
    val bot = SlackBot(api, { }, conversation, bindings, files, config, nowMillis = { 0L }).also { it.selfId = "UBOT" }

    suspend fun TestScope.event(payload: JsonObject) {
        bot.handle("events_api", payload, this)
        advanceUntilIdle()
    }

    suspend fun TestScope.interactive(payload: JsonObject) {
        bot.handle("interactive", payload, this)
        advanceUntilIdle()
    }
}

class SlackBotTest : FunSpec({
    test("연결: 코드만 보내도 되고, 채널 글·봇 글·고친 글은 받지 않는다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("hi")) }, bound = false)
            with(h) {
                event(message("안녕"))
                api.messages.shouldBeEmpty()
                bindings.createPairing(owner.userId, ChannelType.SLACK, PairingCode.hash("ABCD2345"), java.time.Duration.ofMinutes(10))
                event(message("ABCD2345"))
                bindings.find(ChannelType.SLACK, ME)?.externalChatId shouldBe DM
                api.messages.last().text shouldContain "!web 질문"

                val before = api.messages.size
                event(message("질문", channel = "C1", channelType = "channel"))
                event(message("질문", extra = ""","bot_id":"B1""""))
                event(message("질문", extra = ""","subtype":"message_changed""""))
                event(message("질문", from = "UBOT"))
                api.messages.size shouldBe before
                conversation.inputs.shouldBeEmpty()
            }
        }
    }

    test("답변: mrkdwn으로 바꾸고 멘션은 글자로, 인용한 출처만 붙인다. 받은 글의 이스케이프는 되돌린다") {
        runTest {
            val h = Harness({ _, _ ->
                emit(AgentEvent.Sources(listOf(
                    Evidence(SourceType.WEB, "https://example.com/a?x=1&y=2", "예시 <글>", null, "", 1.0, citation = 1),
                    Evidence(SourceType.LOCAL_DOCS, "/Users/me/secret/plan.md", "plan", "3–9행", "", 0.5, citation = 2),
                )))
                emit(AgentEvent.Token("**답**: <!channel> 근거 [1], [2]"))
                emit(AgentEvent.Done(AgentType.WEB, "m", null))
            })
            with(h) {
                event(message("!web a &lt; b"))
                conversation.inputs.single() shouldBe "/web a < b"
                val reply = api.messages.single().text
                reply shouldContain "*답*"
                reply shouldContain "&lt;!channel&gt;"
                reply shouldContain "[1] <https://example.com/a?x=1&amp;y=2|예시 &lt;글&gt;>"
                reply shouldContain "[2] plan.md · 3–9행"
                reply shouldNotContain "/Users/me/secret"
                reply shouldContain "_웹 리서치_"
            }
        }
    }

    test("셸 승인: Block Kit 버튼, 다른 사람은 막고, 수정은 입력 창으로 받는다") {
        runTest {
            val h = Harness({ _, c ->
                val decision = c.open("r1")
                emit(AgentEvent.ApprovalRequested("r1", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", "ls -la <dir>\n작업 폴더: ~/Documents"), "목록 확인", "command", "ls -la"))
                when (val d = decision.await()) {
                    is ApprovalDecision.ApproveEdited -> emit(AgentEvent.Token("실행: " + d.editedArguments["command"]!!.jsonPrimitive.content))
                    else -> emit(AgentEvent.Token("결과: $d"))
                }
                emit(AgentEvent.Done(AgentType.TASK, "m", null))
            })
            with(h) {
                event(message("파일 목록"))
                val card = api.messages.first { it.buttons().isNotEmpty() }
                card.blockText() shouldContain "```\nls -la &lt;dir&gt;\n작업 폴더: ~/Documents\n```"
                card.blockText() shouldContain "목록 확인"
                card.buttons().map { it["value"]!!.jsonPrimitive.content } shouldBe listOf("ap:r1:a", "ap:r1:e", "ap:r1:d")
                card.buttons().map { it["style"]?.jsonPrimitive?.content } shouldBe listOf("primary", null, "danger")

                interactive(action("ap:r1:a", from = "U99", channel = "D99"))
                conversation.decisions.containsKey("r1") shouldBe true
                api.ephemeral.last() shouldBe ("U99" to "권한이 없습니다")

                interactive(action("ap:r1:e"))
                val view = api.views.single()
                view["private_metadata"]!!.jsonPrimitive.content shouldBe "r1"
                view["blocks"]!!.jsonArray[0].jsonObject["element"]!!.jsonObject["initial_value"]!!.jsonPrimitive.content shouldBe "ls -la"

                interactive(submission("r1", "rm -rf ~", from = "U99"))
                conversation.decisions.containsKey("r1") shouldBe true

                interactive(submission("r1", "ls -l"))
                card.buttons().shouldBeEmpty()
                card.blockText() shouldContain "수정해서 승인함"
                card.blockText() shouldContain "```\nls -l\n```"
                api.messages.last().text shouldContain "실행: ls -l"
            }
        }
    }

    test("긴 명령은 블록 여러 개에 나눠 전부 보여 준다") {
        runTest {
            val body = (1..60).joinToString("\n") { "echo '<$it>' && " + "x".repeat(30) }
            val h = Harness({ _, c ->
                c.open("r2")
                emit(AgentEvent.ApprovalRequested("r2", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", body), null))
                kotlinx.coroutines.awaitCancellation()
            })
            with(h) {
                event(message("긴 명령"))
                val card = api.messages.first { it.buttons().isNotEmpty() }
                card.blocks!!.forEach { b -> (b.jsonObject["text"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty().length <= 3000) shouldBe true }
                (1..60).forEach { card.blockText() shouldContain "echo '&lt;$it&gt;'" }
                event(message("!cancel"))
            }
        }
    }

    test("첨부 파일: 버튼으로 고르고, 질문하면 내용을 붙인다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("화요일입니다")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) })
            with(h) {
                val file = ""","subtype":"file_share","files":[{"id":"F1","name":"meeting.md","size":100,"url_private_download":"https://files.slack.com/files-pri/T-F1/download/meeting.md"}]"""
                event(message("", extra = file))
                api.downloads.single() shouldBe "https://files.slack.com/files-pri/T-F1/download/meeting.md"
                val offer = api.messages.last()
                offer.buttons().map { it["text"]!!.jsonObject["text"]!!.jsonPrimitive.content } shouldBe listOf("이 파일에 대해 질문", "'notes' 컬렉션에 추가")
                interactive(action(offer.buttons()[0]["value"]!!.jsonPrimitive.content))
                offer.blocks!!.size shouldBe 0
                event(message("배포일은?"))
                conversation.inputs.single() shouldContain "회의록: 다음 주 화요일에 배포"

                // 설명을 붙여 올리면 바로 질문
                event(message("요약해 줘", extra = file))
                conversation.inputs.last() shouldContain "요약해 줘"
            }
        }
    }
})
