package dev.vesta.conversation

import dev.vesta.core.AgentType
import dev.vesta.router.ExplicitRoute
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.agent.Agent
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.agent.UserInput
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.media.ImageInput
import dev.vesta.core.media.MediaAsset
import dev.vesta.core.media.MediaKind
import dev.vesta.core.media.MediaStore
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.Session
import dev.vesta.core.session.SessionRepository
import dev.vesta.core.session.StoredMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.time.Instant
import java.util.UUID

private class MemorySessions : SessionRepository {
    val items = mutableMapOf<SessionId, Session>()
    override suspend fun create(userId: UserId, channel: ChannelType, title: String?) =
        Session(SessionId(UUID.randomUUID().toString()), userId, title, channel, null, Instant.EPOCH, Instant.EPOCH).also { items[it.id] = it }
    override suspend fun find(userId: UserId, id: SessionId) = items[id]?.takeIf { it.userId == userId }
    override suspend fun listRecent(userId: UserId, limit: Int) = items.values.filter { it.userId == userId }.take(limit)
    override suspend fun updateTitle(userId: UserId, id: SessionId, title: String) = Unit
}

private class MemoryMedia : MediaStore {
    val items = mutableMapOf<String, Pair<MediaAsset, ByteArray>>()
    override suspend fun save(userId: UserId, kind: MediaKind, mime: String, bytes: ByteArray, caption: String?) =
        MediaAsset("a${items.size}", userId, kind, mime, bytes.size.toLong(), caption).also { items[it.id] = it to bytes }
    override suspend fun load(userId: UserId, id: String) = items[id]?.takeIf { it.first.userId == userId }
}

private class MemoryMessages(private val media: MemoryMedia? = null) : MessageRepository {
    val items = mutableListOf<StoredMessage>()
    override suspend fun append(sessionId: SessionId, message: NewMessage) =
        StoredMessage(
            items.size.toLong() + 1, sessionId, message.role, message.content, message.agent, message.model, message.interrupted, Instant.EPOCH,
            message.attachments.mapNotNull { media?.items?.get(it)?.first },
        ).also { items += it }
    override suspend fun list(sessionId: SessionId) = items.filter { it.sessionId == sessionId }
}

private class ScriptedAgent(private val script: suspend kotlinx.coroutines.flow.FlowCollector<AgentEvent>.(AgentRequest) -> Unit) : Agent {
    val requests = mutableListOf<AgentRequest>()
    override val type = AgentType.CHAT
    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        requests += request
        script(request)
    }
}

class DefaultConversationServiceTest : FunSpec({
    val user = UserContext(UserId(1), UserRole.OWNER)

    test("새 세션을 만들고 질문·답변을 저장한다. 다음 턴에는 기록이 넘어간다") {
        val sessions = MemorySessions()
        val messages = MemoryMessages()
        val agent = ScriptedAgent {
            emit(AgentEvent.Token("안녕"))
            emit(AgentEvent.Token("하세요"))
            emit(AgentEvent.Done(AgentType.CHAT, "m", null))
        }
        val service = DefaultConversationService(sessions, messages, mapOf(AgentType.CHAT to agent))

        val first = service.send(user, ChannelType.CLI, null, UserInput("인사해 줘\n둘째 줄")).toList()
        val ready = first.first().shouldBeInstanceOf<AgentEvent.SessionReady>()
        ready.created shouldBe true
        ready.title shouldBe "인사해 줘"

        service.send(user, ChannelType.CLI, ready.sessionId, UserInput("또")).toList()
        messages.items.map { it.role to it.content } shouldBe listOf(
            ChatRole.USER to "인사해 줘\n둘째 줄",
            ChatRole.ASSISTANT to "안녕하세요",
            ChatRole.USER to "또",
            ChatRole.ASSISTANT to "안녕하세요",
        )
        agent.requests.last().history.map { it.content } shouldBe listOf("인사해 줘\n둘째 줄", "안녕하세요", "또")
        messages.items[1].interrupted shouldBe false
    }

    test("중간에 취소해도 생성된 부분은 interrupted로 저장한다") {
        val messages = MemoryMessages()
        val agent = ScriptedAgent {
            emit(AgentEvent.Token("부분"))
            awaitCancellation()
        }
        val service = DefaultConversationService(MemorySessions(), messages, mapOf(AgentType.CHAT to agent))

        // SessionReady, Routed, Token 세 개만 받고 수집을 끝낸다 = 사용자 중단
        service.send(user, ChannelType.CLI, null, UserInput("길게 말해 줘")).take(3).toList()
        val saved = messages.items.last()
        saved.content shouldBe "부분"
        saved.interrupted shouldBe true
    }

    test("에이전트 오류는 Error 이벤트로 바뀌고 빈 답변은 저장하지 않는다") {
        val messages = MemoryMessages()
        val agent = ScriptedAgent { error("엔진 연결 실패") }
        val events = DefaultConversationService(MemorySessions(), messages, mapOf(AgentType.CHAT to agent))
            .send(user, ChannelType.CLI, null, UserInput("hi")).toList()
        events.last() shouldBe AgentEvent.Error("엔진 연결 실패")
        messages.items.filter { it.role == ChatRole.ASSISTANT }.shouldBeEmpty()
    }

    test("다른 사용자의 세션 ID로는 이어갈 수 없다") {
        val sessions = MemorySessions()
        val other = sessions.create(UserId(2), ChannelType.CLI, "남의 것")
        val service = DefaultConversationService(sessions, MemoryMessages(), mapOf(AgentType.CHAT to ScriptedAgent {}))
        shouldThrow<SessionNotFoundException> {
            service.send(user, ChannelType.CLI, other.id, UserInput("hi")).toList()
        }
    }

    test("이미지를 붙이면 저장하고 일반 대화로 보낸다. 후속 질문에는 마지막 이미지를 다시 붙인다") {
        val media = MemoryMedia()
        val messages = MemoryMessages(media)
        val chat = ScriptedAgent { emit(AgentEvent.Token("오류입니다")); emit(AgentEvent.Done(AgentType.CHAT, "m", null)) }
        val web = object : Agent {
            override val type = AgentType.WEB
            override fun handle(request: AgentRequest): Flow<AgentEvent> = flow { error("웹으로 가면 안 됨") }
        }
        val service = DefaultConversationService(MemorySessions(), messages, mapOf(AgentType.CHAT to chat, AgentType.WEB to web), media = media)
        val png = ImageInput.of(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0))!!

        val events = service.send(user, ChannelType.WEB, null, UserInput("/web 이 화면 뭐야?", listOf(png))).toList()
        val session = events.filterIsInstance<AgentEvent.SessionReady>().single().sessionId
        events.filterIsInstance<AgentEvent.Routed>().single().decision.agent shouldBe AgentType.CHAT
        chat.requests.single().history.last().let {
            it.content shouldBe "이 화면 뭐야?"
            it.images.single().mime shouldBe "image/png"
        }
        media.items.size shouldBe 1
        messages.items.first().attachments.single().mime shouldBe "image/png"

        service.send(user, ChannelType.WEB, session, UserInput("두 번째 줄은?")).toList()
        val second = chat.requests.last().history
        second.first().images.size shouldBe 1
        second.last().images.shouldBeEmpty()
    }

    test("글 없이 이미지만 보내면 설명을 부탁한 것으로 본다") {
        val chat = ScriptedAgent { emit(AgentEvent.Done(AgentType.CHAT, "m", null)) }
        val service = DefaultConversationService(MemorySessions(), MemoryMessages(), mapOf(AgentType.CHAT to chat))
        service.send(user, ChannelType.WEB, null, UserInput("", listOf(ImageInput("image/jpeg", byteArrayOf(1))))).toList()
        chat.requests.single().history.last().let {
            it.content shouldBe "이 이미지를 설명해 줘."
            it.images.size shouldBe 1
        }
    }
})

class ExplicitRouteTest : FunSpec({
    test("/docs와 @컬렉션을 해석한다") {
        ExplicitRoute.parse("/docs 메모리 예산은?") shouldBe ExplicitRoute(AgentType.DOCS, emptyList(), "메모리 예산은?")
        ExplicitRoute.parse("@vesta 메모리 예산은?") shouldBe ExplicitRoute(AgentType.DOCS, listOf("vesta"), "메모리 예산은?")
        ExplicitRoute.parse("@a,b @c 질문") shouldBe ExplicitRoute(AgentType.DOCS, listOf("a", "b", "c"), "질문")
        ExplicitRoute.parse("메일 주소는 me@example.com") shouldBe null
        ExplicitRoute.parse("/docsx 질문") shouldBe null
        ExplicitRoute.parse("/web 오늘 환율") shouldBe ExplicitRoute(AgentType.WEB, emptyList(), "오늘 환율")
        ExplicitRoute.parse("이 글 요약해 줘 https://example.com/a") shouldBe ExplicitRoute(AgentType.WEB, emptyList(), "이 글 요약해 줘 https://example.com/a")
    }

    test("@컬렉션 질문은 DocsAgent로 가고 질문에서 지정 부분을 뗀다") {
        val docs = RecordingDocsAgent()
        val chat = ScriptedAgent { error("chat이 불리면 안 됨") }
        val service = DefaultConversationService(MemorySessions(), MemoryMessages(), mapOf(AgentType.CHAT to chat, AgentType.DOCS to docs))
        service.send(UserContext(UserId(1), UserRole.OWNER), ChannelType.CLI, null, UserInput("@vesta 메모리 예산은?")).toList()
        docs.request!!.collections shouldBe listOf("vesta")
        docs.request!!.history.last().content shouldBe "메모리 예산은?"
    }

    test("역할이 허용하지 않는 에이전트는 명시 지정·@이름으로도 쓰지 않고 일반 대화로 보낸다 (U-06)") {
        val docs = RecordingDocsAgent()
        val chat = ScriptedAgent { emit(AgentEvent.Done(AgentType.CHAT, "m", null)) }
        val service = DefaultConversationService(MemorySessions(), MemoryMessages(), mapOf(AgentType.CHAT to chat, AgentType.DOCS to docs))
        val guest = UserContext(UserId(3), UserRole.GUEST)
        val routed = service.send(guest, ChannelType.WEB, null, UserInput("@family 비밀번호 어디 적어 놨지?")).toList()
            .filterIsInstance<AgentEvent.Routed>().single().decision
        routed.agent shouldBe AgentType.CHAT
        routed.requested shouldBe AgentType.DOCS
        docs.request shouldBe null
        // 일반 대화 모델에 넘기지 않고(지어내지 않게) 정해진 안내로 답한다
        chat.requests.shouldBeEmpty()
        val events = service.send(guest, ChannelType.WEB, null, UserInput("/docs 회의록 찾아 줘")).toList()
        events.filterIsInstance<AgentEvent.Token>().single().text shouldContain "문서·코드를 검색할 수 없습니다"
        events.filterIsInstance<AgentEvent.Saved>().size shouldBe 1
    }

    test("요청 큐: 자리가 없으면 대기 상태를 알리고 차례가 되면 답한다") {
        val scheduler = dev.vesta.identity.FairScheduler(1)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val agent = ScriptedAgent { request ->
            if (request.history.last().content == "먼저") gate.await()
            emit(AgentEvent.Token("답"))
            emit(AgentEvent.Done(AgentType.CHAT, "m", null))
        }
        val service = DefaultConversationService(MemorySessions(), MemoryMessages(), mapOf(AgentType.CHAT to agent), scheduler = scheduler)
        kotlinx.coroutines.coroutineScope {
            val first = async { service.send(UserContext(UserId(1), UserRole.OWNER), ChannelType.WEB, null, UserInput("먼저")).toList() }
            kotlinx.coroutines.delay(50)
            val second = async { service.send(UserContext(UserId(2), UserRole.MEMBER), ChannelType.WEB, null, UserInput("다음")).toList() }
            kotlinx.coroutines.delay(50)
            gate.complete(Unit)
            first.await()
            second.await().filterIsInstance<AgentEvent.Status>().map { it.text } shouldBe listOf("다른 요청을 처리하는 중이라 기다립니다 (대기 1번째)")
        }
    }
})

private class RecordingDocsAgent : Agent {
    var request: AgentRequest? = null
    override val type = AgentType.DOCS
    override fun handle(request: AgentRequest): Flow<AgentEvent> = flow {
        this@RecordingDocsAgent.request = request
        emit(AgentEvent.Done(AgentType.DOCS, "m", null))
    }
}
