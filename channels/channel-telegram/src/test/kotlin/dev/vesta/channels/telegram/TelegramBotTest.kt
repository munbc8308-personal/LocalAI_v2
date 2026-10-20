package dev.vesta.channels.telegram

import dev.vesta.channels.PairingCode
import dev.vesta.channels.testing.FakeConversation
import dev.vesta.channels.testing.FakeFiles
import dev.vesta.channels.testing.FakeSpeechToText
import dev.vesta.channels.testing.FakeTextToSpeech
import dev.vesta.channels.testing.MemoryBindings
import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.RiskLevel
import dev.vesta.core.SessionId
import dev.vesta.core.SourceType
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.config.TelegramConfig
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

private val owner = UserContext(UserId(1), UserRole.OWNER)
private const val ME = 42L

private fun text(text: String, from: Long = ME, chatType: String = "private") =
    TgUpdate(1, message = TgMessage(1, TgChat(from, chatType), TgUser(from), text = text))

private fun button(data: String, from: Long = ME, chat: Long = ME) =
    TgUpdate(2, callbackQuery = TgCallbackQuery("cb", TgUser(from), TgMessage(1, TgChat(chat, "private")), data))

private class Harness(
    script: suspend kotlinx.coroutines.flow.FlowCollector<AgentEvent>.(dev.vesta.core.agent.UserInput, FakeConversation) -> Unit,
    config: TelegramConfig = TelegramConfig(streamIntervalMillis = 0),
    bound: Boolean = true,
) {
    val api = FakeTelegram()
    val bindings = MemoryBindings().apply {
        users = mapOf(1L to owner)
        if (bound) items[ME.toString()] = dev.vesta.core.channel.ChannelBinding(owner, ChannelType.TELEGRAM, ME.toString(), ME.toString(), null)
    }
    val conversation = FakeConversation(script)
    val files = FakeFiles()
    val stt = FakeSpeechToText()
    val tts = FakeTextToSpeech()
    val bot = TelegramBot(api, conversation, bindings, files, config, nowMillis = { 0L }, speechToText = stt, textToSpeech = tts)

    suspend fun TestScope.send(update: TgUpdate) {
        bot.handle(update, this)
        advanceUntilIdle()
    }
}

class TelegramBotTest : FunSpec({
    test("연결되지 않은 계정은 답하지 않고, 일회용 코드로만 연결된다. 5번 틀리면 코드가 지워진다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("hi")) }, bound = false)
            with(h) {
                send(text("안녕"))
                api.messages.shouldBeEmpty()
                conversation.inputs.shouldBeEmpty()

                bindings.createPairing(owner.userId, ChannelType.TELEGRAM, PairingCode.hash("ABCD2345"), java.time.Duration.ofMinutes(10))
                repeat(5) { send(text("/start WRONG000", from = 7)) }
                bindings.pairing shouldBe null
                bindings.find(ChannelType.TELEGRAM, "7") shouldBe null

                bindings.createPairing(owner.userId, ChannelType.TELEGRAM, PairingCode.hash("ABCD2345"), java.time.Duration.ofMinutes(10))
                send(text("/start abcd-2345"))
                bindings.find(ChannelType.TELEGRAM, ME.toString())?.user shouldBe owner
                api.messages.last().text shouldContain "연결됐습니다"
                // 코드는 한 번만
                send(text("/start ABCD2345", from = 8))
                bindings.find(ChannelType.TELEGRAM, "8") shouldBe null
            }
        }
    }

    test("그룹 대화는 받지 않는다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("hi")) })
            with(h) {
                send(text("안녕", chatType = "group"))
                api.messages.shouldBeEmpty()
            }
        }
    }

    test("답변을 고쳐 가며 보여 주고, 끝나면 HTML과 인용한 출처만 붙인다. 세션을 이어 간다") {
        runTest {
            val h = Harness({ input, _ ->
                emit(AgentEvent.SessionReady(SessionId("s1"), null, created = true))
                emit(AgentEvent.Sources(listOf(
                    Evidence(SourceType.WEB, "https://example.com/a", "예시 <글>", null, "", 1.0, citation = 1),
                    Evidence(SourceType.LOCAL_DOCS, "/Users/me/secret/plan.md", "plan", "3–9행", "", 0.5, citation = 2),
                    Evidence(SourceType.WEB, "https://example.com/unused", "안 쓴 자료", null, "", 0.1, citation = 3),
                )))
                emit(AgentEvent.Token("**답**: <b>태그</b> "))
                emit(AgentEvent.Token("근거 [1], [2]"))
                emit(AgentEvent.Done(AgentType.WEB, "m", null))
            })
            with(h) {
                send(text("질문"))
                val reply = api.messages.single()
                reply.html shouldBe true
                reply.text shouldContain "<b>답</b>: &lt;b&gt;태그&lt;/b&gt;"
                reply.text shouldContain "<a href=\"https://example.com/a\">예시 &lt;글&gt;</a>"
                reply.text shouldContain "[2] plan.md · 3–9행"
                reply.text shouldNotContain "/Users/me/secret"
                reply.text shouldNotContain "unused"
                reply.text shouldContain "웹 리서치"
                bindings.find(ChannelType.TELEGRAM, ME.toString())?.sessionId shouldBe SessionId("s1")

                send(text("다음 질문"))
                conversation.sessions.last() shouldBe SessionId("s1")
                send(text("/new"))
                send(text("새 질문"))
                conversation.sessions.last() shouldBe null
            }
        }
    }

    test("긴 답변은 여러 메시지로 나눈다") {
        runTest {
            val h = Harness({ _, _ ->
                repeat(30) { emit(AgentEvent.Token("문단 $it " + "가".repeat(300) + "\n\n")) }
                emit(AgentEvent.Done(AgentType.CHAT, "m", null))
            })
            with(h) {
                send(text("길게"))
                (api.messages.size > 2) shouldBe true
                api.messages.forEach { (it.text.length <= 4096) shouldBe true }
                api.messages.last().text shouldContain "문단 29"
            }
        }
    }

    test("HTML이 거부되면 글자 그대로 보낸다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("**굵게**")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) })
            with(h) {
                api.rejectHtml = true
                send(text("질문"))
                api.messages.single().text shouldBe "**굵게**"
            }
        }
    }

    test("셸 승인: 다른 사람은 누를 수 없고, 본인이 수정해서 승인하면 수정한 명령이 간다") {
        runTest {
            val h = Harness({ _, c ->
                val decision = c.open("r1")
                emit(AgentEvent.ApprovalRequested("r1", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", "ls -la\n작업 폴더: ~/Documents"), "목록 확인", "command", "ls -la"))
                when (val d = decision.await()) {
                    is ApprovalDecision.ApproveEdited -> emit(AgentEvent.Token("실행: " + d.editedArguments["command"]!!.jsonPrimitive.content))
                    else -> emit(AgentEvent.Token("결과: $d"))
                }
                emit(AgentEvent.Done(AgentType.TASK, "m", null))
            })
            with(h) {
                send(text("파일 목록"))
                val approval = api.messages.first { it.keyboard != null }
                approval.text shouldContain "<pre>ls -la\n작업 폴더: ~/Documents</pre>"
                approval.text shouldContain "목록 확인"
                approval.keyboard!!.rows.single().map { it.text } shouldBe listOf("승인", "수정", "거부")

                send(button("ap:r1:a", from = 99, chat = 99))
                h.conversation.decisions.containsKey("r1") shouldBe true
                api.callbackAnswers.last() shouldBe "권한이 없습니다"

                send(button("ap:r1:e"))
                api.messages.last().forceReply shouldBe true
                send(text("ls -l"))
                approval.keyboard shouldBe null
                approval.text shouldContain "수정해서 승인함: <code>ls -l</code>"
                // 승인 뒤 답변은 승인 카드 아래 새 메시지로
                api.messages.first().text shouldBe "아래 요청을 확인해 주세요."
                api.messages.last().text shouldContain "실행: ls -l"
                api.messages.indexOfLast { it.text.contains("실행: ls -l") } shouldBe api.messages.size - 1

                // 끝난 요청은 다시 누를 수 없다
                send(button("ap:r1:a"))
                api.callbackAnswers.last() shouldBe "이미 끝난 요청입니다"
            }
        }
    }

    test("거부 버튼과 셸 승인 끄기") {
        runTest {
            val script: suspend kotlinx.coroutines.flow.FlowCollector<AgentEvent>.(dev.vesta.core.agent.UserInput, FakeConversation) -> Unit = { _, c ->
                val decision = c.open("r2")
                emit(AgentEvent.ApprovalRequested("r2", "shell", RiskLevel.EXEC, ApprovalDetails("셸 실행", "rm -rf ~/tmp"), null))
                emit(AgentEvent.Token("결정: ${decision.await()::class.simpleName}"))
                emit(AgentEvent.Done(AgentType.TASK, "m", null))
            }
            val h = Harness(script)
            with(h) {
                send(text("지워"))
                send(button("ap:r2:d"))
                api.messages.last().text shouldContain "결정: Deny"
                api.messages.first { it.text.contains("승인 필요") }.text shouldContain "→ 거부함"
            }
            val off = Harness(script, TelegramConfig(streamIntervalMillis = 0, allowExecApproval = false))
            with(off) {
                send(text("지워"))
                api.messages.none { it.keyboard != null } shouldBe true
                api.messages.any { it.text.contains("allowExecApproval") } shouldBe true
                api.messages.last().text shouldContain "결정: Deny"
            }
        }
    }

    test("/cancel로 멈추면 생성된 데까지 보여 주고 승인 버튼을 닫는다") {
        runTest {
            val h = Harness({ _, c ->
                emit(AgentEvent.Token("앞부분"))
                c.open("r3")
                emit(AgentEvent.ApprovalRequested("r3", "write_file", RiskLevel.WRITE, ApprovalDetails("파일 쓰기", "내용"), null))
                awaitCancellation()
            })
            with(h) {
                send(text("질문"))
                send(text("다른 질문"))
                api.messages.last().text shouldContain "앞 질문에 답하는 중"
                send(text("/cancel"))
                api.messages.first().text shouldBe "앞부분"
                api.messages.last().text shouldBe "(중단됨)"
                api.messages.first { it.text.contains("승인 필요") }.let {
                    it.keyboard shouldBe null
                    it.text shouldContain "만료됨"
                }
            }
        }
    }

    test("로컬 자료 보내기를 끄면 문서 답변은 보내지 않는다") {
        runTest {
            val h = Harness({ _, _ ->
                emit(AgentEvent.Routed(dev.vesta.core.decision.RoutingDecision(AgentType.DOCS, 1.0, dev.vesta.core.decision.RoutingStage.EXPLICIT, question = "질문")))
                emit(AgentEvent.Token("비밀 문서 내용"))
                emit(AgentEvent.Done(AgentType.DOCS, "m", null))
            }, TelegramConfig(streamIntervalMillis = 0, allowLocalSources = false))
            with(h) {
                send(text("/docs 질문"))
                api.messages.forEach { it.text shouldNotContain "비밀" }
                api.messages.last().text shouldContain "allowLocalSources"
            }
        }
    }

    test("파일: 설명 없이 보내면 질문/컬렉션 추가를 고르고, 질문하면 파일 내용을 붙여 일반 대화로 보낸다") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("화요일입니다")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) })
            with(h) {
                val doc = TgUpdate(3, message = TgMessage(5, TgChat(ME, "private"), TgUser(ME), document = TgDocument("f1", "meeting.md", 100)))
                send(doc)
                val offer = api.messages.last()
                offer.keyboard!!.rows.flatten().map { it.text } shouldBe listOf("이 파일에 대해 질문", "'notes' 컬렉션에 추가")
                send(button(offer.keyboard!!.rows[0][0].callbackData))
                send(text("배포일은?"))
                conversation.inputs.single().let {
                    it shouldContain "/chat 첨부 파일 \"meeting.md\""
                    it shouldContain "회의록: 다음 주 화요일에 배포"
                    it shouldContain "배포일은?"
                }

                send(doc)
                send(button(api.messages.last().keyboard!!.rows[1][0].callbackData))
                files.added.single().first shouldBe "notes"
                api.messages.last().text shouldContain "색인 완료"

                send(TgUpdate(4, message = TgMessage(6, TgChat(ME, "private"), TgUser(ME), document = TgDocument("f2", "photo.exe", 100))))
                api.messages.last().text shouldContain ".txt .md .pdf"
            }
        }
    }

    test("음성: 인식한 글을 먼저 보여 주고 그 글로 묻는다. /voice를 켜면 답도 음성으로") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("**오후 3시**입니다 [1]")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) })
            with(h) {
                api.file = "OggS-voice".toByteArray()
                val voice = TgUpdate(5, message = TgMessage(7, TgChat(ME, "private"), TgUser(ME), voice = TgVoice("v1", 3, "audio/ogg", 9)))
                send(voice)
                stt.received.single().decodeToString() shouldBe "OggS-voice"
                api.messages.first().text shouldBe "🎤 내일 회의 몇 시야"
                conversation.inputs.single() shouldBe "내일 회의 몇 시야"
                api.voices.shouldBeEmpty()

                send(text("/voice"))
                api.messages.last().text shouldContain "음성으로도 답합니다"
                send(voice)
                tts.spoken.single() shouldBe "**오후 3시**입니다 [1]"
                api.voices.size shouldBe 1

                // 글로 물으면 음성으로 답하지 않는다
                send(text("다음 질문"))
                api.voices.size shouldBe 1

                stt.text = ""
                send(voice)
                api.messages.last().text shouldContain "알아듣지 못했습니다"
            }
        }
    }

    test("사진: 설명을 질문으로, 가장 큰 사진을 이미지로 붙인다. 이미지가 아니면 거절") {
        runTest {
            val h = Harness({ _, _ -> emit(AgentEvent.Token("메모리 부족 오류입니다")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) })
            with(h) {
                api.file = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)
                val photo = TgMessage(
                    8, TgChat(ME, "private"), TgUser(ME), caption = "이 오류 뭐야?",
                    photo = listOf(TgPhotoSize("small", 90, 90, 100), TgPhotoSize("big", 1280, 960, 90_000)),
                )
                send(TgUpdate(6, message = photo))
                conversation.userInputs.single().let {
                    it.text shouldBe "이 오류 뭐야?"
                    it.images.single().mime shouldBe "image/jpeg"
                }
                api.messages.last().text shouldContain "메모리 부족"

                // 문서로 보낸 이미지도 이미지로
                api.file = "<html>".toByteArray()
                send(TgUpdate(7, message = TgMessage(9, TgChat(ME, "private"), TgUser(ME), document = TgDocument("d1", "shot.png", 6))))
                api.messages.last().text shouldContain "PNG·JPEG"
                conversation.userInputs.size shouldBe 1
            }
        }
    }


    test("만든 이미지는 답변을 마친 뒤 사진으로 보낸다") {
        runTest {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            val asset = dev.vesta.core.media.MediaAsset("g1", owner.userId, dev.vesta.core.media.MediaKind.GENERATED, "image/png", 4, "a cat")
            val h = Harness({ _, _ ->
                emit(AgentEvent.Media(asset, png, "a cat"))
                emit(AgentEvent.Token("이미지를 만들었습니다"))
                emit(AgentEvent.Done(AgentType.IMAGE, "FLUX.1-schnell", null))
            })
            with(h) {
                send(text("/image 고양이"))
                conversation.inputs.single() shouldBe "/image 고양이"
                api.photos.single().let {
                    it.first.toList() shouldBe png.toList()
                    it.second shouldBe "AI 생성 · a cat"
                }
                api.messages.last().text shouldContain "이미지를 만들었습니다"
            }
        }
    }
})
