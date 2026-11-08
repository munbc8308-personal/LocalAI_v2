package dev.vesta.agents

import dev.vesta.core.ChannelType
import dev.vesta.core.ModelRole
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.inference.ModelInfo
import dev.vesta.core.media.GeneratedImage
import dev.vesta.core.media.ImageGenerator
import dev.vesta.core.media.MediaAsset
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.MediaKind
import dev.vesta.core.media.MediaStore
import dev.vesta.core.model.ModelProvider
import dev.vesta.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import java.time.Instant

internal class OneReply(private val text: String) : InferenceClient {
    val requests = mutableListOf<ChatRequest>()
    override suspend fun listModels() = emptyList<ModelInfo>()
    override suspend fun embed(model: String, texts: List<String>) = emptyList<FloatArray>()
    override fun chat(request: ChatRequest): Flow<ChatChunk> {
        requests += request
        return flowOf(ChatChunk.Delta(text), ChatChunk.Done("stop", null))
    }
}

internal class FakeGenerator(private val failure: String? = null) : ImageGenerator {
    override val model = "FLUX.1-schnell"
    val calls = mutableListOf<Triple<String, Int, Int>>()
    /** 기준 그림과 남길 정도 (V-11) */
    val references = mutableListOf<Pair<ByteArray?, Double>>()
    override suspend fun generate(prompt: String, width: Int, height: Int, steps: Int, seed: Long?, reference: ByteArray?, keep: Double): GeneratedImage {
        failure?.let { throw MediaException(it) }
        calls += Triple(prompt, width, height)
        references += reference to keep
        return GeneratedImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), 7)
    }
}

internal class SavedMedia : MediaStore {
    val saved = mutableListOf<MediaAsset>()
    override suspend fun save(userId: UserId, kind: MediaKind, mime: String, bytes: ByteArray, caption: String?) =
        MediaAsset("g${saved.size}", userId, kind, mime, bytes.size.toLong(), caption).also { saved += it }
    override suspend fun load(userId: UserId, id: String) = null
}

class ImageAgentTest : FunSpec({
    val user = UserContext(UserId(1), UserRole.OWNER)
    val session = Session(SessionId("s"), user.userId, null, ChannelType.WEB, null, Instant.EPOCH, Instant.EPOCH)
    val models = object : ModelProvider { override suspend fun acquire(role: ModelRole) = ModelSpec("m") }

    test("한국어 요청을 영어 프롬프트로 바꾸고, 자리를 비운 뒤 그려서 갤러리에 저장한다") {
        val llm = OneReply("""{"prompt": "a watercolor cat sleeping on a windowsill, soft morning light", "aspect": "portrait"}""")
        val generator = FakeGenerator()
        val media = SavedMedia()
        var reserved = 0
        val agent = ImageAgent(llm, models, generator, media, reserve = { reserved++ })
        val events = agent.handle(AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "창가에서 자는 고양이 수채화로 세로로 그려 줘")))).toList()

        reserved shouldBe 1
        generator.calls.single() shouldBe Triple("a watercolor cat sleeping on a windowsill, soft morning light", 768, 1024)
        media.saved.single().let {
            it.kind shouldBe MediaKind.GENERATED
            it.caption shouldBe "a watercolor cat sleeping on a windowsill, soft morning light"
        }
        events.filterIsInstance<AgentEvent.Media>().single().bytes.size shouldBe 4
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text } shouldContain "프롬프트: `a watercolor cat"
        llm.requests.single().messages.single().content shouldContain "창가에서 자는 고양이"
    }

    test("수정 요청은 이전 프롬프트가 든 대화를 함께 넘긴다") {
        val llm = OneReply("""{"prompt": "a brighter watercolor cat", "aspect": "square"}""")
        val agent = ImageAgent(llm, models, FakeGenerator(), SavedMedia())
        agent.handle(AgentRequest(user, session, listOf(
            ChatMessage(ChatRole.USER, "고양이 그려 줘"),
            ChatMessage(ChatRole.ASSISTANT, "이미지를 만들었습니다.\n\n프롬프트: `a watercolor cat`"),
            ChatMessage(ChatRole.USER, "더 밝게"),
        ))).toList()
        llm.requests.single().messages.single().content shouldContain "프롬프트: `a watercolor cat`"
    }

    test("프롬프트를 못 만들면 요청 그대로, 모델 준비 중이면 안내만 하고 저장하지 않는다") {
        val generator = FakeGenerator(failure = "이미지 모델을 처음 준비하고 있습니다")
        val media = SavedMedia()
        val events = ImageAgent(OneReply("잘 모르겠어요"), models, generator, media)
            .handle(AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "노을 진 바다")))).toList()
        media.saved.size shouldBe 0
        events.filterIsInstance<AgentEvent.Token>().single().text shouldContain "준비하고"
        events.last().javaClass.simpleName shouldBe "Done"
        ImageAgent(OneReply("x"), models, FakeGenerator(), media).plan(listOf(ChatMessage(ChatRole.USER, "노을 진 바다"))).prompt shouldBe "노을 진 바다"
    }

    test("기준 그림 (V-11): 붙인 그림을 모델에게 보여 주고, 그 모양대로 잘라 기준으로 넘긴다. 남길 정도는 모델이 고른다. 실제 인물을 속이는 편집은 거절") {
        val photo = dev.vesta.core.media.ImageInput("image/png", java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(800, 400, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray())
        val llm = OneReply("""{"prompt": "the same street as a watercolor painting", "aspect": "landscape", "keep": "high", "refuse": null}""")
        val generator = FakeGenerator()
        val events = ImageAgent(llm, models, generator, SavedMedia())
            .handle(AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "원본 느낌 살려서 수채화로", images = listOf(photo))))).toList()
        llm.requests.single().messages.single().let { it.images.size shouldBe 1; it.content shouldContain "aspect: \"landscape\"" }
        generator.calls.single() shouldBe Triple("the same street as a watercolor painting", 1024, 768)
        generator.references.single().let { (ref, keep) ->
            keep shouldBe ImageAgent.KEEP_HIGH
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(ref)).let { it.width shouldBe 1024; it.height shouldBe 768 }
        }
        events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text } shouldContain "붙인 그림 기준 · 원본과 비슷하게"

        val refused = FakeGenerator()
        ImageAgent(OneReply("""{"prompt": "", "refuse": "실제 인물을 속이는 사진은 만들지 않아요"}"""), models, refused, SavedMedia())
            .handle(AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "이 사람이 체포되는 사진으로", images = listOf(photo))))).toList()
            .filterIsInstance<AgentEvent.Token>().single().text shouldContain "만들지 않습니다"
        refused.calls.size shouldBe 0
    }

    test("보안 검토 (압축 폭탄): 헤더 크기가 4천만 화소를 넘는 그림은 풀지 않고 알린다") {
        // 작은 파일이지만 헤더에 10000×10000이라고 적은 PNG (IHDR만 고침)
        val small = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val bomb = small.copyOf().also { b ->
            java.nio.ByteBuffer.wrap(b, 16, 8).putInt(10_000).putInt(10_000)
            val crc = java.util.zip.CRC32().apply { update(b, 12, 17) }.value
            java.nio.ByteBuffer.wrap(b, 29, 4).putInt(crc.toInt())
        }
        ImageFit.size(bomb) shouldBe (10_000 to 10_000)
        ImageFit.oversized(bomb) shouldBe true
        ImageFit.cover(bomb, 64, 64) shouldBe null
        ImageFit.oversized(small) shouldBe false
        val generator = FakeGenerator()
        ImageAgent(OneReply("x"), models, generator, SavedMedia())
            .handle(AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, "수채화로", images = listOf(dev.vesta.core.media.ImageInput("image/png", bomb)))))).toList()
            .filterIsInstance<AgentEvent.Token>().single().text shouldContain "너무 큽니다"
        generator.calls.size shouldBe 0
    }
})
