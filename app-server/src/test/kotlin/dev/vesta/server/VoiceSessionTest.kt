package dev.vesta.server

import dev.vesta.core.AgentType
import dev.vesta.core.SessionId
import dev.vesta.core.agent.AgentEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelChildren

class VoiceSessionTest : FunSpec({
    /** 20ms 조각: 크기 [amp]의 사인파 (PCM16 LE 16kHz) */
    fun frame(amp: Double): ByteArray {
        val n = 320
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (kotlin.math.sin(i * 0.3) * amp * 32767).toInt().toShort()
            out[2 * i] = (v.toInt() and 0xff).toByte(); out[2 * i + 1] = (v.toInt() shr 8).toByte()
        }
        return out
    }

    test("말이 끝나면 받아 적고 답을 글·소리로 흘려보낸다, 답하는 중에 말하면 끊는다 (M37)") { runBlocking {
        val events = mutableListOf<String>()
        val audio = mutableListOf<ByteArray>()
        val hold = CompletableDeferred<Unit>()
        var answers = 0
        val voice = VoiceSession(
            this,
            transcribe = { wav -> if (wav.size > 44) "날씨 어때" else "" },
            answer = { q ->
                answers++
                flow {
                    emit(AgentEvent.SessionReady(SessionId("s1"), "제목", true))
                    emit(AgentEvent.Token("맑아요. "))
                    if (answers == 1) emit(AgentEvent.Token("기온은 20도예요."))
                    else hold.await()
                    emit(AgentEvent.Done(AgentType.CHAT, "m", null))
                }
            },
            speak = { text -> text.toByteArray() },
            sendText = { events += it },
            sendAudio = { audio += it },
        )
        repeat(25) { voice.onAudio(frame(0.001)) }      // 조용함: 소음 기준
        repeat(30) { voice.onAudio(frame(0.3)) }        // 0.6초 말
        repeat(40) { voice.onAudio(frame(0.001)) }      // 0.8초 침묵 → 끝
        withTimeout(5_000) { while (events.none { "\"done\"" in it }) delay(10) }
        events.any { "\"final\"" in it && "날씨 어때" in it } shouldBe true
        events.any { "\"session\"" in it && "s1" in it } shouldBe true
        audio.map { it.decodeToString() } shouldContain "맑아요. 기온은 20도예요."
        // 두 번째 질문: 답하는 중(hold)에 다시 크게 말하면 끊는다
        repeat(30) { voice.onAudio(frame(0.3)) }
        repeat(40) { voice.onAudio(frame(0.001)) }
        withTimeout(5_000) { while (events.count { "\"final\"" in it } < 2) delay(10) }
        delay(100)
        repeat(30) { voice.onAudio(frame(0.9)) }
        withTimeout(5_000) { while (events.none { "\"interrupt\"" in it }) delay(10) }
        hold.complete(Unit)
        coroutineContext.cancelChildren()
    } }
})
