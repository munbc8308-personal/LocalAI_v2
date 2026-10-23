package dev.vesta.router

import dev.vesta.core.AgentType
import dev.vesta.core.config.DecisionConfig
import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingStage
import dev.vesta.core.inference.ChatMessage
import dev.vesta.decision.RoutingQuestions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * 에이전트 선택 (PROJECT.md 4.5).
 * 1 명시 지정 → 2 규칙 → 3 결정 모델(신뢰도 ≥ 임계값일 때만) → 4 메인 모델 JSON 판정 → 5 일반 대화.
 * 판정된 에이전트가 아직 없으면 [available]에서 대신 쓸 에이전트를 고르고 원래 판정은 `requested`에 남긴다.
 */
class Router(
    private val config: DecisionConfig,
    private val decision: DecisionClient?,
    private val llm: LlmRouter?,
) {
    /**
     * @param available 지금 쓸 수 있는 에이전트 (구현됨 + 조건 충족, 예: 컬렉션이 있어야 DOCS)
     * @param recent 직전 대화 (이번 질문 제외)
     */
    suspend fun route(
        text: String,
        recent: List<ChatMessage>,
        available: Set<AgentType>,
        /** 같은 세션의 직전 판정. 짧은 후속 질문("그럼 코드 모델은?")은 이 에이전트·컬렉션을 이어받는다 */
        previous: RoutingDecision? = null,
    ): RoutingDecision {
        val start = TimeSource.Monotonic.markNow()
        fun done(decision: RoutingDecision) = substitute(decision.copy(latencyMs = start.elapsedNow().inWholeMilliseconds), available)

        // 1~2단계: 명시 지정, URL 규칙
        ExplicitRoute.parse(text)?.let { route ->
            val stage = if (text.trim().startsWith("/") || text.trim().startsWith("@")) RoutingStage.EXPLICIT else RoutingStage.RULE
            return done(RoutingDecision(route.agent, 1.0, stage, collections = route.collections, question = route.question))
        }
        if (CODE_BLOCK.containsMatchIn(text)) return done(RoutingDecision(AgentType.CODE, 1.0, RoutingStage.RULE, question = text))
        // 영상 만들기 (M24): 결정 모델 선택지에 영상이 없어(학습한 D1을 바꾸지 않으려고) 확실한 말은 규칙으로
        if (VIDEO_REQUEST.containsMatchIn(text) && !NOT_VIDEO.containsMatchIn(text)) return done(RoutingDecision(AgentType.VIDEO, 1.0, RoutingStage.RULE, question = text))

        // 영상 고치기 (M24 G-05): 직전이 영상이고 장면·내레이션·자막 등을 고치라는 말이면 영상으로 ("2번 장면을 노을로 바꿔"가 그림으로 간 적이 있다)
        val videoRecently = previous?.agent == AgentType.VIDEO ||
            recent.takeLast(4).any { it.role == dev.vesta.core.inference.ChatRole.ASSISTANT && VIDEO_ANSWER.containsMatchIn(it.content) }
        if (videoRecently && VIDEO_EDIT.containsMatchIn(text) && !NOT_VIDEO.containsMatchIn(text)) {
            return done(RoutingDecision(AgentType.VIDEO, 1.0, RoutingStage.RULE, question = text))
        }
        // 2.5단계: 짧은 후속 질문은 직전 정보원(문서·웹·코드·개인 비서)을 이어간다.
        // M7에서 발견: "그럼 코드 모델은?"을 일반 대화로 보내 근거 없이 지어낸 답이 나왔다
        if (previous != null && recent.isNotEmpty() && text.length < FOLLOW_UP_CHARS * 2 && previous.agent in CONTINUABLE &&
            FOLLOW_UP.containsMatchIn(text.trim())
        ) {
            return done(RoutingDecision(previous.agent, previous.confidence, RoutingStage.RULE, previous.needsWeb, previous.needsLocal, previous.collections, text))
        }

        // 3단계: 결정 모델. 이어지는 짧은 질문("그거 더 자세히")은 문맥이 필요하므로 메인 모델로 넘긴다
        val model = decision
        if (config.enabled && model != null && !(recent.isNotEmpty() && text.length < FOLLOW_UP_CHARS)) {
            runCatching { model.decide(text, RoutingQuestions.questions(config.criteriaLanguage)) }
                .onFailure { log.warn(it) { "결정 모델 실패, 다음 단계로" } }
                .getOrNull()
                ?.let { answers ->
                    val agent = answers[RoutingQuestions.AGENT] as? DecisionAnswer.Choice
                    val web = (answers[RoutingQuestions.NEEDS_WEB] as? DecisionAnswer.Noul)?.probability
                    val local = (answers[RoutingQuestions.NEEDS_LOCAL] as? DecisionAnswer.Noul)?.probability
                    val type = agent?.value?.let { runCatching { AgentType.valueOf(it) }.getOrNull() }
                    if (type != null && agent.confidence >= config.thresholds.agent) {
                        // 그림을 고른 영상 요청 ("여행 영상 하나 뽑아 줘")
                        val chosen = if (type == AgentType.IMAGE && VIDEO_WORD.containsMatchIn(text)) AgentType.VIDEO else type
                        return done(RoutingDecision(chosen, agent.confidence, RoutingStage.DECISION_MODEL, web, local, question = text))
                    }
                }
        }

        // 4단계: 메인 모델 판정
        llm?.let { router ->
            runCatching { router.judge(text, recent) }
                .onFailure { log.warn(it) { "메인 모델 라우팅 실패" } }
                .getOrNull()
                ?.let { verdict ->
                    return done(
                        RoutingDecision(
                            agent = verdict.agent,
                            confidence = verdict.confidence,
                            stage = RoutingStage.LLM,
                            needsWeb = if (verdict.needsWeb) 1.0 else 0.0,
                            needsLocal = if (verdict.needsLocal) 1.0 else 0.0,
                            question = text,
                        ),
                    )
                }
        }

        // 5단계: 일반 대화
        return done(RoutingDecision(AgentType.CHAT, 0.0, RoutingStage.DEFAULT, question = text))
    }

    /** 아직 없는 에이전트 대신 쓸 에이전트. 코드 질문은 같은 메인 모델이 일반 대화로 답할 수 있다. */
    private fun substitute(decision: RoutingDecision, available: Set<AgentType>): RoutingDecision {
        if (decision.agent in available) return decision
        val fallback = when {
            decision.agent == AgentType.DOCS && AgentType.DOCS !in available -> AgentType.CHAT
            (decision.needsWeb ?: 0.0) >= config.thresholds.needsWeb && AgentType.WEB in available -> AgentType.WEB
            else -> AgentType.CHAT
        }
        return decision.copy(agent = fallback, requested = decision.agent)
    }

    private companion object {
        val CODE_BLOCK = Regex("```")
        private val VIDEO_WORD = Regex("영상|동영상|비디오|쇼츠|릴스|숏폼|브이로그|슬라이드쇼|\\bvideo\\b", RegexOption.IGNORE_CASE)
        /** "영상 만들어 줘", "30초 쇼츠 제작해 줘": 영상 낱말 바로 뒤(10자 안)에 만들기 */
        val VIDEO_REQUEST = Regex("(영상|동영상|비디오|쇼츠|릴스|숏폼|슬라이드쇼|\\bvideo\\b)\\s*(을|를|으로|로|하나|한 편|좀)?\\s*.{0,6}(만들어|만들자|제작해|생성해|뽑아|make|create)", RegexOption.IGNORE_CASE)
        /** VideoAgent가 남긴 답 */
        val VIDEO_ANSWER = Regex("영상을 (만들었|고쳤)습니다")
        /** 앞 영상을 고치는 말 (G-05) */
        val VIDEO_EDIT = Regex("\\d+\\s*(번|번째)\\s*(장면|컷)|장면|내레이션|나레이션|자막|배경 ?음악|끝 화면|영상|더 (밝게|어둡게|빠르게|느리게|짧게|길게)|빠르게|느리게|세로로|가로로")
        /** 영상을 다루는 코드·영상 내용 요약·방법 질문은 영상 만들기가 아니다 */
        val NOT_VIDEO = Regex("코드|스크립트|프로그램|함수|파이썬|python|ffmpeg|요약|자막 추출|분석|방법|어떻게|앱 추천|프로그램 추천", RegexOption.IGNORE_CASE)
        /** 이보다 짧은 후속 질문은 결정 모델 대신 문맥을 보는 메인 모델이 판정 */
        const val FOLLOW_UP_CHARS = 20
        /** 이미지: "더 밝게", "그럼 고양이로" 같은 수정 요청은 이어서 다시 그린다 */
        val CONTINUABLE = setOf(AgentType.DOCS, AgentType.WEB, AgentType.CODE, AgentType.PERSONAL, AgentType.IMAGE, AgentType.VIDEO)
        /** 앞 질문에 기대는 말로 시작하거나 주어가 빠진 짧은 질문 */
        val FOLLOW_UP = Regex("^(그럼|그러면|그건|그거|그것|이건|이거|저건|또|더|그리고|그 ?다음|다른|나머지|위|아까|방금)|[은는]\\s*[?？]?$|^(왜|어떻게|언제|어디서|누가)\\s*\\S{0,6}[?？]$")
    }
}
