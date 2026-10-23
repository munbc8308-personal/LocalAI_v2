package dev.vesta.agents

import dev.vesta.core.ChannelType
import dev.vesta.core.ModelRole
import dev.vesta.core.session.Session
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.MediaKind
import dev.vesta.core.media.Motion
import dev.vesta.core.media.TextToSpeech
import dev.vesta.core.media.VideoComposer
import dev.vesta.core.media.VideoScene
import dev.vesta.core.model.ModelProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import java.time.Instant

private class FakeComposer : VideoComposer {
    var scenes = emptyList<VideoScene>()
    var size = 0 to 0
    override suspend fun compose(scenes: List<VideoScene>, width: Int, height: Int, title: String, music: ByteArray?, onScene: suspend (Int) -> Unit): ByteArray {
        this.scenes = scenes; size = width to height
        return ByteArray(2_000)
    }
}

class VideoAgentTest : FunSpec({
    val user = UserContext(UserId(1), UserRole.OWNER)
    val session = Session(SessionId("s"), user.userId, null, ChannelType.WEB, null, Instant.EPOCH, Instant.EPOCH)
    val models = object : ModelProvider { override suspend fun acquire(role: ModelRole) = ModelSpec("m") }
    fun request(text: String) = AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, text)))

    test("요청한 길이·장면 수·크기") {
        VideoAgent.targetSeconds("우리 동네 맛집 소개 30초 영상") shouldBe 30
        VideoAgent.targetSeconds("1분짜리 여행 영상") shouldBe 60
        VideoAgent.targetSeconds("영상 만들어 줘") shouldBe 30
        VideoAgent.targetSeconds("5초 영상") shouldBe 10
        VideoAgent.sceneCount(30) shouldBe 6
        VideoAgent.sceneCount(10) shouldBe 3
        VideoAgent.sceneCount(180) shouldBe VideoAgent.MAX_SCENES
        VideoAgent.imageSize("portrait") shouldBe (576 to 1024)
        VideoAgent.videoSize("landscape") shouldBe (1280 to 720)
    }

    test("계획 읽기: 코드 블록 안 JSON, 모르는 움직임은 확대, 빠진 장면은 버림, 거절") {
        val agent = VideoAgent(OneReply(""), models, FakeGenerator(), FakeComposer(), SavedMedia())
        val plan = agent.parse("""```json
            {"title": "동네 맛집", "aspect": "portrait", "refuse": null, "scenes": [
              {"narration": "골목 끝 작은 국숫집.", "image": "a cozy noodle shop", "motion": "pan_left"},
              {"narration": "국물이 진하다.", "image": "steaming noodle bowl", "motion": "spin"},
              {"narration": "그림이 없는 장면"}
            ]}```""")!!
        plan.title shouldBe "동네 맛집"
        plan.aspect shouldBe "portrait"
        plan.scenes.map { it.motion } shouldBe listOf(Motion.PAN_LEFT, Motion.ZOOM_IN)
        agent.parse("""{"title": "x", "refuse": "실존 인물의 얼굴을 흉내 냄", "scenes": []}""")!!.refuse shouldBe "실존 인물의 얼굴을 흉내 냄"
        agent.parse("그냥 글") shouldBe null
    }

    test("장면마다 그림·내레이션을 만들고 합쳐 갤러리에 영상으로 남긴다. 자막은 내레이션 그대로, 대본은 답변에") {
        val llm = OneReply("""{"title": "가을 산책", "aspect": "landscape", "scenes": [
            {"narration": "단풍이 물든 길.", "image": "autumn path, red maple leaves", "motion": "zoom_in"},
            {"narration": "벤치에 앉아 쉰다.", "image": "wooden bench in an autumn park", "motion": "pan_right"}]}""")
        val generator = FakeGenerator()
        val composer = FakeComposer()
        val media = SavedMedia()
        val spoken = mutableListOf<String>()
        val tts = object : TextToSpeech {
            override suspend fun synthesize(text: String, format: AudioFormat): ByteArray { spoken += text; return byteArrayOf(9) }
        }
        var reserved = 0
        val events = VideoAgent(llm, models, generator, composer, media, tts, reserve = { reserved++ }).handle(request("가을 산책 10초 영상 만들어 줘")).toList()
        reserved shouldBe 1
        generator.calls.map { it.second to it.third } shouldBe listOf(1024 to 576, 1024 to 576)
        spoken shouldBe listOf("단풍이 물든 길.", "벤치에 앉아 쉰다.")
        composer.size shouldBe (1280 to 720)
        composer.scenes.map { it.subtitle to it.motion } shouldBe listOf("단풍이 물든 길." to Motion.ZOOM_IN, "벤치에 앉아 쉰다." to Motion.PAN_RIGHT)
        media.saved.single().let { it.kind shouldBe MediaKind.VIDEO; it.mime shouldBe "video/mp4"; it.caption shouldBe "가을 산책" }
        events.filterIsInstance<AgentEvent.Media>().single().asset.mime shouldBe "video/mp4"
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }.let {
            it shouldContain "가을 산책"
            it shouldContain "2. 벤치에 앉아 쉰다."
            it shouldContain "AI 생성"
        }
        llm.requests.single().messages.single().content shouldContain "exactly 3 scenes"
    }

    test("실제 인물을 흉내 내라는 요청은 그리지 않고 거절한다") {
        val generator = FakeGenerator()
        val events = VideoAgent(OneReply("""{"title": "x", "refuse": "실존 연예인의 얼굴을 흉내 냄", "scenes": []}"""), models, generator, FakeComposer(), SavedMedia())
            .handle(request("유명 배우 얼굴로 광고 영상 만들어 줘")).toList()
        generator.calls.size shouldBe 0
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text } shouldContain "만들지 않습니다"
    }
})
