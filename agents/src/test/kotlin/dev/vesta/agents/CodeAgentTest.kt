package dev.vesta.agents

import dev.vesta.core.ChannelType
import dev.vesta.core.ModelRole
import dev.vesta.core.SessionId
import dev.vesta.core.SourceType
import dev.vesta.core.UserContext
import dev.vesta.core.UserRole
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.AgentRequest
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.DecisionQuestion
import dev.vesta.core.inference.ChatChunk
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.inference.InferenceClient
import dev.vesta.core.inference.ModelInfo
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.knowledge.RetrieveOptions
import dev.vesta.core.knowledge.Retriever
import dev.vesta.core.model.ModelProvider
import dev.vesta.core.session.Session
import dev.vesta.decision.Judge
import dev.vesta.storage.KnowledgeRepository
import dev.vesta.storage.VestaDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import java.time.Instant
import kotlin.io.path.createTempDirectory

class CodeAgentTest : FunSpec({
    val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
    val owner = db.ensureOwner("owner")
    val user = UserContext(owner, UserRole.OWNER)
    val session = Session(SessionId("s"), owner, null, ChannelType.CLI, null, Instant.EPOCH, Instant.EPOCH)
    val knowledge = KnowledgeRepository(db.db)
    kotlinx.coroutines.runBlocking { knowledge.createCollection(owner, "vesta", null, "embed", CollectionKind.CODE, "/tmp/vesta") }
    val retriever = object : Retriever {
        override val source = SourceType.CODE
        override suspend fun retrieve(user: dev.vesta.core.UserId, queries: List<String>, options: RetrieveOptions) =
            listOf(Evidence(SourceType.CODE, "/tmp/vesta/WebAuth.kt", "WebAuth.kt", "1–20행", "class WebAuth { fun login() }", 0.9))
    }
    val models = object : ModelProvider { override suspend fun acquire(role: ModelRole) = ModelSpec("main") }
    class Recording : InferenceClient {
        var request: ChatRequest? = null
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun embed(model: String, texts: List<String>) = emptyList<FloatArray>()
        override fun chat(request: ChatRequest): Flow<ChatChunk> { this.request = request; return flowOf(ChatChunk.Delta("답"), ChatChunk.Done("stop", null)) }
    }
    /** D6·D7 모두 [p]로 답하는 결정 모델 */
    fun judge(p: Double) = Judge(object : DecisionClient {
        override suspend fun decide(state: String, questions: Map<String, DecisionQuestion>) = questions.mapValues { DecisionAnswer.Noul(p) }
    })
    fun ask(agent: CodeAgent, question: String) =
        agent.handle(AgentRequest(user, session, listOf(ChatMessage(ChatRole.USER, question)), listOf("vesta")))

    test("찾은 코드로 답할 수 없으면(D6 낮음) 먼저 밝히고 코드 없이 일반 지식으로 답한다") {
        val inference = Recording()
        val events = ask(CodeAgent(inference, models, knowledge, retriever, judge = judge(0.08)), "JWT 토큰은 어떻게 동작해?").toList()
        val answer = events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }
        answer shouldStartWith "vesta 코드에서는 이 질문과 관련된 내용을 찾지 못했습니다"
        inference.request!!.messages.last().content shouldNotContain "코드 자료"
        events.filterIsInstance<AgentEvent.Sources>().size shouldBe 0
    }

    test("답할 수 있으면 코드 자료를 붙여 답한다 (코드 조각은 D6가 낮게 나오므로 0.2 기준)") {
        val inference = Recording()
        val events = ask(CodeAgent(inference, models, knowledge, retriever, judge = judge(0.32)), "로그인 잠금은 어디서 해?").toList()
        inference.request!!.messages.last().content shouldContain "코드 자료"
        events.filterIsInstance<AgentEvent.Token>().first().text shouldBe "답"
    }
})
