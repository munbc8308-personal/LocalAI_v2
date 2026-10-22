package dev.vesta.channels.discord.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class VoiceTurnsTest : FunSpec({
    val frame = ByteArray(3840) // 20ms

    test("0.8초 조용하면 그 사람 말이 끝난다. 사람마다 따로, 짧은 소리는 버린다") {
        val turns = VoiceTurns()
        repeat(50) { turns.onAudio("a", frame, 1_000L + it * 20) } // 1초
        repeat(5) { turns.onAudio("b", frame, 1_000L + it * 20) } // 0.1초
        turns.finished(1_500).shouldBeEmpty()
        val done = turns.finished(1_000 + 49 * 20 + 800)
        done.map { it.userId } shouldBe listOf("a")
        done.single().seconds shouldBe 1.0
        turns.finished(5_000).shouldBeEmpty()
    }

    test("너무 길면 끊는다") {
        val turns = VoiceTurns(maxSeconds = 1.0)
        repeat(50) { turns.onAudio("a", frame, it * 20L) }
        turns.finished(50 * 20L).single().seconds shouldBe 1.0
    }

    test("16kHz 모노 WAV: 길이 1/3, 머리글") {
        val wav = Pcm.toWav16k(ByteArray(48_000 * 4))
        wav.size shouldBe 44 + 16_000 * 2
        String(wav, 0, 4) shouldBe "RIFF"
    }

    test("호출어: 앞에 있을 때만, 부르는 말 끝(야·님)과 문장부호를 뗀다") {
        val words = listOf("베스타", "vesta")
        WakeWord.strip("베스타야, 오늘 날씨 어때?", words) shouldBe "오늘 날씨 어때?"
        WakeWord.strip("Vesta 알려 줘", words) shouldBe "알려 줘"
        WakeWord.strip("… 베스타!", words) shouldBe ""
        WakeWord.strip("오늘 베스타는 쉬어", words) shouldBe null
        WakeWord.isStop("그만해") shouldBe true
        WakeWord.isStop("그 만두") shouldBe false
    }

    test("합성 음성 앞뒤 무음을 0.08초만 남기고 자른다") {
        val silence = ByteArray(48_000 * 4 / 2) // 0.5초
        val loud = ByteArray(48_000 * 4 / 10) { if (it % 2 == 0) 0x20 else 0 } // 0.1초, 큰 소리
        val trimmed = Pcm.trimSilence(silence + loud + silence)
        (trimmed.size / (48_000.0 * 4)) shouldBe (0.1 + 0.16 plusOrMinus 0.001)
        Pcm.trimSilence(silence).size shouldBe 0
    }

    test("사람별 한 시간 횟수: 넘으면 막고, 한 시간이 지나면 다시") {
        var now = 0L
        val budget = DiscordVoiceService.Budget(2) { now }
        budget.allow("a") shouldBe true
        budget.allow("a") shouldBe true
        budget.allow("a") shouldBe false
        budget.allow("b") shouldBe true
        now = 3_600_001
        budget.allow("a") shouldBe true
    }

    test("나중 조각은 길게 묶는다") {
        val text = "첫 문장은 이렇습니다. 둘째 문장입니다. 셋째 문장도 있어요. 넷째"
        val first = Sentences.cut(text, 0, false)
        text.substring(0, first) shouldBe "첫 문장은 이렇습니다. "
        // 80자가 안 되면 기다린다
        Sentences.cut(text, first, false, minChars = 80) shouldBe first
        Sentences.cut(text, first, false, minChars = 20) shouldBe "첫 문장은 이렇습니다. 둘째 문장입니다. 셋째 문장도 있어요. ".length
    }

    test("문장 단위로 끊기") {
        val text = "네. 내일 서울은 맑겠습니다. 최고 기온은"
        Sentences.cut(text, 0, false) shouldBe "네. 내일 서울은 맑겠습니다. ".length
        Sentences.cut(text, text.length - 6, true) shouldBe text.length
    }
})
