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

private class MemoryMedia : dev.vesta.core.media.MediaStore {
    val files = linkedMapOf<String, Pair<dev.vesta.core.media.MediaAsset, ByteArray>>()
    override suspend fun save(userId: UserId, kind: MediaKind, mime: String, bytes: ByteArray, caption: String?) =
        dev.vesta.core.media.MediaAsset("a${files.size}", userId, kind, mime, bytes.size.toLong(), caption).also { files[it.id] = it to bytes }
    override suspend fun load(userId: UserId, id: String) = files[id]?.takeIf { it.first.userId == userId }
}

private class MemoryProjects : dev.vesta.core.media.VideoProjectRepository {
    val saved = mutableListOf<Pair<String, dev.vesta.core.media.VideoProject>>()
    override suspend fun save(userId: UserId, sessionId: String, project: dev.vesta.core.media.VideoProject) { saved += sessionId to project }
    override suspend fun latest(userId: UserId, sessionId: String) = saved.lastOrNull { it.first == sessionId }?.second
}

/** 부를 때마다 다음 답을 */
private class Replies(vararg val texts: String) : dev.vesta.core.inference.InferenceClient {
    val requests = mutableListOf<dev.vesta.core.inference.ChatRequest>()
    override suspend fun listModels() = emptyList<dev.vesta.core.inference.ModelInfo>()
    override suspend fun embed(model: String, texts: List<String>) = emptyList<FloatArray>()
    override fun chat(request: dev.vesta.core.inference.ChatRequest): kotlinx.coroutines.flow.Flow<dev.vesta.core.inference.ChatChunk> {
        requests += request
        return kotlinx.coroutines.flow.flowOf(dev.vesta.core.inference.ChatChunk.Delta(texts[requests.size - 1]), dev.vesta.core.inference.ChatChunk.Done("stop", null))
    }
}

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
        media.saved.single { it.kind == MediaKind.VIDEO }.let { it.mime shouldBe "video/mp4"; it.caption shouldBe "가을 산책" }
        media.saved.count { it.kind == MediaKind.SCENE } shouldBe 4 // 그림 2 + 내레이션 2 (고치기용)
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

    test("고치기 (G-05): 앞 영상의 같은 그림·내레이션은 다시 쓰고 바뀐 장면만 새로, 빠르기, 비율은 앞 영상 그대로") {
        val first = """{"mode": "new", "title": "가을 산책", "aspect": "landscape", "scenes": [
            {"narration": "단풍이 물든 길.", "image": "autumn path", "motion": "zoom_in"},
            {"narration": "벤치에 앉아 쉰다.", "image": "bench in a park", "motion": "pan_right"}]}"""
        val edit = """{"mode": "edit", "title": "가을 산책", "aspect": "portrait", "speed": 1.2, "scenes": [
            {"narration": "단풍이 물든 길.", "image": "autumn path", "motion": "zoom_in"},
            {"narration": "벤치에 앉아 쉰다.", "image": "bench in a park, brighter sunlight", "motion": "pan_right"}]}"""
        val llm = Replies(first, edit)
        val generator = FakeGenerator()
        val composer = FakeComposer()
        val media = MemoryMedia()
        val projects = MemoryProjects()
        val spoken = mutableListOf<String>()
        val tts = object : TextToSpeech {
            override suspend fun synthesize(text: String, format: AudioFormat): ByteArray { spoken += text; return text.toByteArray() }
        }
        val agent = VideoAgent(llm, models, generator, composer, media, tts, projects = projects)
        agent.handle(request("가을 산책 10초 영상 만들어 줘")).toList()
        generator.calls.size shouldBe 2
        spoken.size shouldBe 2
        projects.saved.single().second.scenes.size shouldBe 2

        val events = agent.handle(request("2번 장면 더 밝게, 내레이션은 조금 빠르게")).toList()
        generator.calls.size shouldBe 3
        generator.calls.last().first shouldBe "bench in a park, brighter sunlight"
        spoken.size shouldBe 2
        composer.size shouldBe (1280 to 720)
        composer.scenes.map { it.speed } shouldBe listOf(1.2, 1.2)
        composer.scenes.map { String(it.narration!!) } shouldBe listOf("단풍이 물든 길.", "벤치에 앉아 쉰다.")
        projects.saved.last().second.let { it.speed shouldBe 1.2; it.aspect shouldBe "landscape" }
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }.let {
            it shouldContain "영상을 고쳤습니다"
            it shouldContain "그림 1개, 내레이션 0개"
        }
        llm.requests.last().messages.single().content.let {
            it shouldContain "Previous video"
            it shouldContain "bench in a park"
        }
        media.files.values.count { it.first.kind == MediaKind.VIDEO } shouldBe 2
    }

    test("움직이는 클립 (G-03): animate 장면만, 최대 개수까지. 실패하면 그림으로") {
        val llm = OneReply("""{"title": "바다", "aspect": "landscape", "scenes": [
            {"narration": "파도가 친다.", "image": "waves", "motion": "zoom_in", "animate": true},
            {"narration": "갈매기가 난다.", "image": "seagulls", "motion": "pan_left", "animate": true},
            {"narration": "노을이 진다.", "image": "sunset", "motion": "zoom_out", "animate": true}]}""")
        val animated = mutableListOf<String>()
        val clips = object : dev.vesta.core.media.ClipGenerator {
            override val model = "wan"
            override suspend fun animate(image: ByteArray, prompt: String, width: Int, height: Int, seconds: Double): ByteArray {
                animated += prompt
                if (prompt == "seagulls") throw dev.vesta.core.media.MediaException("메모리 부족")
                return ByteArray(10)
            }
        }
        val composer = FakeComposer()
        var reservedClips = 0
        VideoAgent(llm, models, FakeGenerator(), composer, MemoryMedia(), clips = clips, maxClips = 2, reserveClips = { reservedClips++ })
            .handle(request("바다 영상 만들어 줘")).toList()
        animated shouldBe listOf("waves", "seagulls")
        reservedClips shouldBe 1
        composer.scenes.map { it.clip != null } shouldBe listOf(true, false, false)
    }

    test("백그라운드 작업이 있으면 계획만 하고 넘긴 뒤 답을 바로 마친다. 작업이 장면을 만들고 결과를 돌려준다") {
        val llm = OneReply("""{"title": "가을 산책", "aspect": "landscape", "scenes": [
            {"narration": "단풍이 물든 길.", "image": "autumn path", "motion": "zoom_in"}]}""")
        val generator = FakeGenerator()
        val composer = FakeComposer()
        var captured: (suspend (suspend (String) -> Unit) -> dev.vesta.core.media.JobResult)? = null
        val jobs = object : dev.vesta.core.media.BackgroundJobs {
            override fun submit(user: UserContext, session: dev.vesta.core.session.Session, title: String, agent: dev.vesta.core.AgentType,
                                work: suspend (suspend (String) -> Unit) -> dev.vesta.core.media.JobResult): dev.vesta.core.media.JobState {
                captured = work
                return dev.vesta.core.media.JobState("j1", user.userId, session.id.value, title, dev.vesta.core.media.JobStatus.QUEUED)
            }
            override fun get(userId: UserId, id: String) = null
        }
        val events = VideoAgent(llm, models, generator, composer, MemoryMedia(), jobs = jobs).handle(request("가을 산책 영상 만들어 줘")).toList()
        generator.calls.size shouldBe 0
        events.filterIsInstance<AgentEvent.JobStarted>().single().let { it.id shouldBe "j1"; it.title shouldBe "가을 산책" }
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text } shouldContain "끝나면 이 대화에 영상을 올리고 알려 드릴게요"
        events.filterIsInstance<AgentEvent.Media>().size shouldBe 0
        val progress = mutableListOf<String>()
        val result = captured!!.invoke { progress += it }
        generator.calls.size shouldBe 1
        progress.first() shouldBe "장면 1/1 그리는 중"
        result.media!!.mime shouldBe "video/mp4"
        result.text shouldContain "영상을 만들었습니다"
    }
})
