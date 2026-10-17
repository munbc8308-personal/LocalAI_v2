package dev.vesta.cli

import dev.vesta.agents.ChatAgent
import dev.vesta.agents.DocsAgent
import dev.vesta.agents.QueryRewriter
import dev.vesta.agents.WebResearchAgent
import dev.vesta.retrieval.WebRetriever
import dev.vesta.storage.WebCacheRepository
import dev.vesta.web.HttpPageFetcher
import dev.vesta.web.SearxngClient
import dev.vesta.conversation.DefaultConversationService
import dev.vesta.core.AgentType
import dev.vesta.core.UserContext
import dev.vesta.core.UserRole
import dev.vesta.core.config.ConfigLoader
import dev.vesta.core.config.ModelSpec
import dev.vesta.core.config.VestaConfig
import dev.vesta.core.config.VestaPaths
import dev.vesta.inference.HttpClients
import dev.vesta.inference.ModelManager
import dev.vesta.inference.OllamaAdmin
import dev.vesta.inference.OpenAiCompatibleClient
import dev.vesta.storage.ExposedMessageRepository
import dev.vesta.storage.ExposedSessionRepository
import dev.vesta.storage.KnowledgeRepository
import dev.vesta.knowledge.Indexer
import dev.vesta.retrieval.HttpReranker
import dev.vesta.retrieval.LocalRetriever
import dev.vesta.retrieval.VectorIndex
import dev.vesta.storage.VestaDatabase

/**
 * 구현체 연결 (PROJECT.md 10.1: DI는 app-* 모듈에서). M8에서 app-server도 같은 구성을 쓴다.
 * [generalOverride]가 있으면 설정의 메인 모델 대신 쓴다.
 */
class AppContainer(generalOverride: String? = null) : AutoCloseable {
    val paths = VestaPaths.default()
    val config: VestaConfig = ConfigLoader.load(paths.configFile).let { loaded ->
        if (generalOverride == null) loaded
        else loaded.copy(models = loaded.models.copy(general = (loaded.models.general ?: DEFAULT_GENERAL).copy(name = generalOverride)))
    }

    private val http = HttpClients.local(config.inference.requestTimeoutSeconds)
    private val database = VestaDatabase.open(paths.database).also { it.migrate() }

    val sessions = ExposedSessionRepository(database.db)
    val messages = ExposedMessageRepository(database.db)
    val inference = OpenAiCompatibleClient(config.inference.baseUrl, http)
    val modelManager = ModelManager(
        models = config.models,
        budgetBytes = MacSystem.totalMemoryBytes() * config.memory.budgetPercent / 100,
        runtime = OllamaAdmin(config.inference.ollamaUrl, http),
    )
    val knowledge = KnowledgeRepository(database.db)
    val indexer = Indexer(knowledge, inference)
    /** 리랭커는 선택 (models.reranker, engine: llama-server). 없으면 RRF 순서만 쓴다. */
    val reranker = config.models.reranker?.takeIf { it.baseUrl != null }?.let { HttpReranker(it.baseUrl!!, it.name, http) }
    val retriever = LocalRetriever(knowledge, inference, VectorIndex(knowledge), reranker)

    /** 외부 웹용 HTTP 클라이언트 (리다이렉트 따라감). 로컬 엔진용과 분리한다. */
    private val webHttp = HttpClients.web(config.web.pageTimeoutSeconds)
    val webCache = WebCacheRepository(database.db)
    val webRetriever = WebRetriever(
        search = SearxngClient(config.web.searxngUrl, http, webCache),
        fetcher = HttpPageFetcher(webHttp, config.web, webCache),
        inference = inference,
        embeddingModel = config.models.embedding?.name ?: DEFAULT_EMBEDDING,
        maxPages = config.web.maxPages,
    )
    val conversation = DefaultConversationService(
        sessions = sessions,
        messages = messages,
        agents = mapOf(
            AgentType.CHAT to ChatAgent(inference, modelManager),
            AgentType.DOCS to DocsAgent(inference, modelManager, knowledge, retriever),
            AgentType.WEB to WebResearchAgent(inference, modelManager, QueryRewriter(inference, modelManager), webRetriever, { config.web.mode }),
        ),
    )

    /** 단일 사용자 동안은 OWNER 한 명 (PROJECT.md 7.12). */
    val owner = UserContext(database.ensureOwner(System.getProperty("user.name") ?: "owner"), UserRole.OWNER)

    override fun close() {
        http.close()
        webHttp.close()
    }

    companion object {
        /** 설정에 메인 모델이 없을 때 --model과 함께 쓰는 기본 파라미터 (M0 벤치마크: 생각 양 low). */
        val DEFAULT_GENERAL = ModelSpec(name = "", keepAlive = "forever", reasoningEffort = "none")
        const val DEFAULT_EMBEDDING = "bge-m3:567m"
    }
}
