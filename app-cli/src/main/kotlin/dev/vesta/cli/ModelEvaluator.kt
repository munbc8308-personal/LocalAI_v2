package dev.vesta.cli

import dev.vesta.agents.Prompts
import dev.vesta.app.AppContainer
import dev.vesta.core.ModelRole
import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionQuestion
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.inference.ChatRequest
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.knowledge.CollectionKind
import dev.vesta.core.knowledge.RetrieveOptions
import dev.vesta.decision.OllayaClient
import dev.vesta.decision.RoutingQuestions
import dev.vesta.evolution.RouteSample
import dev.vesta.evolution.Scorecard
import dev.vesta.inference.OllamaAdmin
import dev.vesta.retrieval.ContextAssembler
import dev.vesta.router.LlmRouter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import kotlin.io.path.exists

/**
 * 메인 모델 하나를 주간 점검 평가셋으로 잰다 (PROJECT.md 7.13 E-03). [app]은 그 모델을 메인 모델로 둔 앱 구성이다.
 * - 답변 내용: eval/answers-ko.tsv 질문을 대화 에이전트처럼 묻고, 핵심 내용을 담았는지 결정 모델이 항목마다 판정
 * - 라우팅: eval/routing-ko.tsv (결정 모델이 못 정한 질문은 메인 모델이 판정하므로 모델마다 다르다)
 * - 근거율: 문서 컬렉션 질문의 D7 결과
 * - 한국어(한자·빈 답변·조사 띄움), 도구 호출 JSON 형식, 답변 시작 시간·속도·메모리
 */
internal class ModelEvaluator(private val evalDir: Path, private val echo: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    /** 마지막 [score]의 문항별 답변·판정 (점검 기록 폴더에 남겨 결정 모델 판정을 사람이 확인할 수 있게) */
    var transcript: String = ""
        private set

    suspend fun score(app: AppContainer): Scorecard {
        val spec = app.modelManager.acquire(ModelRole.GENERAL)
        val decision = OllayaClient(app.config.decision.baseUrl, { app.decisionModel.current() }, app.httpForEval)
        val system = Prompts.base(Clock.systemDefaultZone(), ZoneId.systemDefault())

        suspend fun ask(question: String, systemPrompt: String = system) = StreamStats.collect(
            app.inference.chat(
                ChatRequest(
                    spec.name,
                    listOf(ChatMessage(ChatRole.SYSTEM, systemPrompt), ChatMessage(ChatRole.USER, question)),
                    // 평가끼리 비교하려고 온도를 낮게 고정한다
                    temperature = 0.2, maxTokens = 1_024, reasoningEffort = spec.reasoningEffort, keepAlive = "10m",
                ),
            ),
        )
        // 첫 호출은 모델 로드가 들어가 따로 뺀다
        ask("안녕하세요. 한 문장으로 답해 주세요.")

        echo("  답변 내용 평가 (${spec.name})…")
        val answerRows = readTsv(evalDir.resolve("answers-ko.tsv"))
        val outputs = mutableListOf<StreamStats>()
        val log = StringBuilder("# ${spec.name}\n")
        var covered = 0.0
        for (row in answerRows) {
            val (question, factsText) = row
            val stats = ask(question)
            outputs += stats
            val facts = factsText.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            // `=글자`: 답에 그 글자가 있어야, `!글자`: 있으면 안 된다 (정확한 값은 결정 모델이 후하게 판정한다:
            // 첫 점검에서 "[2, 4, 6]"이라고 틀린 답을 "[4, 6]"으로 0.61 판정). 나머지는 결정 모델이 판정
            val judged = facts.withIndex().filter { (_, fact) -> fact[0] != '=' && fact[0] != '!' }
            val answers = if (judged.isEmpty()) emptyMap() else runCatching {
                decision.decide(
                    "Question: $question\n\nAnswer: ${stats.text.take(4_000)}",
                    judged.associate { (i, fact) -> "f$i" to DecisionQuestion.Noul("Does the answer state or clearly imply the following? $fact") },
                )
            }.getOrDefault(emptyMap())
            val compact = literal(stats.text)
            val probabilities = facts.mapIndexed { i, fact ->
                when (fact[0]) {
                    '=' -> if (literal(fact.substring(1)) in compact) 1.0 else 0.0
                    '!' -> if (literal(fact.substring(1)) in compact) 0.0 else 1.0
                    else -> (answers["f$i"] as? DecisionAnswer.Noul)?.probability ?: 0.0
                }
            }
            val hits = probabilities.count { it >= FACT_THRESHOLD }
            covered += hits.toDouble() / facts.size
            log.append("\n## $question\n\n${stats.text.trim()}\n\n")
            facts.zip(probabilities).forEach { (fact, p) -> log.append("- ${if (p >= FACT_THRESHOLD) "✓" else "✗"} ${"%.2f".format(p)} $fact\n") }
            if (hits < facts.size) echo(dim("    ${hits}/${facts.size} ${question.take(40)}"))
        }

        echo("  도구 호출 형식…")
        val toolOk = TOOL_PROMPTS.count { (request, tool) ->
            val text = ask(request, TOOL_SYSTEM).text
            validToolCall(text, tool).also { log.append("\n## 도구: $request\n\n${if (it) "✓" else "✗"} ${text.trim().take(300)}\n") }
        }
        transcript = log.toString()

        echo("  라우팅…")
        val routing = EvalRoutingCommand.score(app, evalDir.resolve("routing-ko.tsv"))

        // 근거율: 문서 컬렉션이 있으면 문서 질문, 없으면 코드 저장소 질문 (CodeAgent도 D7로 답변 문장을 검증한다)
        val grounding = groundingSource(app)?.let { (collection, file) ->
            echo("  근거율 ($collection)…")
            evalGrounding(app, collection, file, GROUNDING_QUESTIONS, cleanup = true) { echo("  $it") }
                .let { if (it.claims == 0) null else 1.0 - it.unsupported.toDouble() / it.claims }
        }

        val memory = runCatching {
            OllamaAdmin(app.config.inference.ollamaUrl, app.httpForEval).loadedModels().firstOrNull { it.name == spec.name }?.sizeBytes
        }.getOrNull()
        val clean = outputs.count { it.text.isNotBlank() && KoreanStyle.hanWords(it.text).isEmpty() && KoreanStyle.spacedParticles(it.text) == 0 }
        return Scorecard(
            model = spec.name,
            routing = routing,
            answers = covered / maxOf(1, answerRows.size),
            grounding = grounding,
            korean = clean.toDouble() / maxOf(1, outputs.size),
            toolJson = toolOk.toDouble() / TOOL_PROMPTS.size,
            emptyAnswers = outputs.count { it.text.isBlank() },
            firstAnswerMs = outputs.map { it.firstAnswerMs }.median(),
            tokensPerSecond = outputs.map { it.decodeTokensPerSecond }.median(),
            memoryBytes = memory,
        )
    }

    private suspend fun groundingSource(app: AppContainer): Pair<String, Path>? {
        if (app.judge == null) return null
        val evolution = app.config.evolution
        val docs = evalDir.resolve("retrieval-project-md.tsv")
        if (docs.exists() && app.knowledge.findCollection(app.owner.userId, evolution.docsCollection)?.kind == CollectionKind.DOCS) return evolution.docsCollection to docs
        val code = evalDir.resolve("code-vesta.tsv")
        if (code.exists() && app.knowledge.findCollection(app.owner.userId, evolution.codeCollection)?.kind == CollectionKind.CODE) return evolution.codeCollection to code
        return null
    }

    /** 라우터 임계값 조정용: 문항마다 결정 모델 판정·신뢰도와 메인 모델 판정 (E-06) */
    suspend fun routeSamples(app: AppContainer): List<RouteSample> {
        val decision = OllayaClient(app.config.decision.baseUrl, { app.decisionModel.current() }, app.httpForEval)
        val llm = LlmRouter(app.inference, app.modelManager)
        val questions = RoutingQuestions.questions(app.config.decision.criteriaLanguage)
        return readTsv(evalDir.resolve("routing-ko.tsv")).mapNotNull { row ->
            val expected = runCatching { dev.vesta.core.AgentType.valueOf(row[1]) }.getOrNull() ?: return@mapNotNull null
            val choice = runCatching { decision.decide(row[0], questions)[RoutingQuestions.AGENT] as? DecisionAnswer.Choice }.getOrNull()
            val agent = choice?.value?.let { runCatching { dev.vesta.core.AgentType.valueOf(it) }.getOrNull() }
            RouteSample(row[0], expected, agent, choice?.confidence ?: 0.0, runCatching { llm.judge(row[0], emptyList())?.agent }.getOrNull())
        }
    }

    /** D6 임계값 조정용: 저장소에 답이 있는 질문·없는 질문의 D6 점수 (CodeAgent와 같은 검색·자료 묶기, [AppContainer.codeContext]) */
    suspend fun codeSufficiency(app: AppContainer): Pair<List<Pair<String, Double>>, List<Pair<String, Double>>>? {
        val judge = app.judge ?: return null
        app.knowledge.findCollection(app.owner.userId, app.config.evolution.codeCollection)?.takeIf { it.kind == CollectionKind.CODE } ?: return null
        val answerableFile = evalDir.resolve("code-vesta.tsv")
        val absentFile = evalDir.resolve("code-absent-ko.tsv")
        if (!answerableFile.exists() || !absentFile.exists()) return null
        suspend fun score(question: String): Double {
            val context = app.codeContext(question) ?: return 0.0
            return judge.sufficient(question, context.text)
        }
        return readTsv(answerableFile).map { it[0] to score(it[0]) } to readTsv(absentFile).map { it[0] to score(it[0]) }
    }

    /** 글자 비교용: 공백을 하나로, 천 단위 쉼표를 뺀다 ("1,024"도 "1024"로 맞게) */
    private fun literal(text: String) = text.trim().replace(Regex("\\s+"), " ").replace(Regex("(?<=\\d),(?=\\d{3})"), "")

    private fun validToolCall(text: String, tool: String): Boolean = runCatching {
        val body = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
        val call = json.parseToJsonElement(body).jsonObject
        call["tool"]!!.jsonPrimitive.content == tool && call["arguments"] is JsonObject
    }.getOrDefault(false)

    private companion object {
        const val GROUNDING_QUESTIONS = 10
        /** 결정 모델 판정 기준. 맞는 답은 대부분 0.9 이상, 틀린 정확한 값을 0.61로 받아들인 적이 있어 0.5보다 높게 */
        const val FACT_THRESHOLD = 0.7
        val TOOL_SYSTEM = """
            너는 도구를 호출하는 비서다. 반드시 JSON 한 개만 출력한다. 설명 문장은 쓰지 않는다.
            형식: {"tool": 도구 이름, "arguments": {…}}
            도구: calendar_event_create(title, start, end: ISO8601), reminder_create(title, due: ISO8601), weather_get(city)
            오늘은 2026-10-25이고 시간대는 Asia/Seoul이다.
        """.trimIndent()
        val TOOL_PROMPTS = listOf(
            "내일 오전 10시에 '주간 회의' 일정을 한 시간짜리로 만들어 줘." to "calendar_event_create",
            "모레 저녁 7시에 엄마한테 전화하라고 알려 줘." to "reminder_create",
            "부산 날씨 어때?" to "weather_get",
        )
    }
}
