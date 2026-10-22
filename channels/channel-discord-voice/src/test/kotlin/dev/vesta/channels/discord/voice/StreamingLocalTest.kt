package dev.vesta.channels.discord.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** 이 Mac에 vesta-speak가 있을 때만: 문장을 시간 차를 두고 넣어도 끝까지 소리가 나오는지 (M19 실사용: 한두 문장 뒤 끊김) */
class StreamingLocalTest : FunSpec({
    val binary = Path.of(System.getProperty("user.home"), ".vesta/bin/vesta-speak")
    test("이어 말하기 + Speaker: 긴 답을 토큰처럼 흘려도 끝까지").config(enabled = java.nio.file.Files.isExecutable(binary)) {
        val streaming = dev.vesta.media.AvSpeechStreaming(binary, "Yuna")
        val speaker = DiscordVoiceService.Speaker()
        val text = "오늘 신문들을 보니까 세상 돌아가는 소식이 참 많네. 우선 조금 눈에 띄는 건 로봇 기술 관련 뉴스야. " +
            "유니트리라는 곳에서 보디가드 로봇을 공개했는데, 괴한을 막아내고 무려 사 미터 높이까지 점프할 수 있다고 해 [1]. " +
            "또 무인잠수정인 고스트 샤크에서 드론을 발사하는 영상이 처음으로 공개됐다는 소식도 있어 [1]."
        var bytes = 0L
        val speech = streaming.open { bytes += it.size; speaker.enqueue(it) }
        var spoken = 0
        val acc = StringBuilder()
        for (token in text.chunked(3)) {
            acc.append(token)
            val end = Sentences.cut(acc.toString(), spoken, false)
            if (end > spoken) { speech.say(acc.substring(spoken, end)); spoken = end }
            Thread.sleep(10)
        }
        val end = Sentences.cut(acc.toString(), spoken, true)
        if (end > spoken) speech.say(acc.substring(spoken, end))
        speech.finish()
        speech.await()
        speech.close()
        val seconds = bytes / (48_000.0 * 4)
        println("합성 %.1f초".format(seconds))
        var frames = 0
        while (speaker.canProvide()) { speaker.provide20MsAudio(); frames++ }
        println("보낸 프레임 $frames (%.1f초)".format(frames * 0.02))
        (seconds > 15) shouldBe true
    }
})
