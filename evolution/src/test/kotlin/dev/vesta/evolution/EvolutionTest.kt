package dev.vesta.evolution

import dev.vesta.core.AgentType
import dev.vesta.core.config.EvolutionConfig
import dev.vesta.core.config.TunedSettings
import dev.vesta.core.evolution.ProposalKind
import dev.vesta.core.evolution.ProposalStatus
import dev.vesta.storage.ExposedEvolutionRepository
import dev.vesta.storage.VestaDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

class EvolutionTest : FunSpec({
    fun resource(name: String) = EvolutionTest::class.java.getResource("/$name")!!.readText()

    test("ollama.com 태그 페이지: 이름·digest·크기 (크기가 없는 cloud 태그는 빠진다)") {
        val tags = OllamaLibraryHtml.tags(resource("ollama-tags.html"))
        tags.map { it.name } shouldContainExactly listOf(
            "gemma4:26b-mxfp8", "gemma4:31b-mxfp8", "gemma4:31b-nvfp4", "gemma4:12b-mxfp8", "gemma4:e2b-mxfp8", "gemma4:31b-it-q8_0",
        )
        tags.first() shouldBe CatalogTag("gemma4:26b-mxfp8", "aee6e7cf8945", 29_000_000_000)
    }

    test("ollama.com 새 모델 목록: 이름·기능·크기") {
        val families = OllamaLibraryHtml.families(resource("ollama-newest.html"))
        families.map { it.name } shouldContainExactly listOf("flint", "d1-omni", "beam", "mistral-large-4")
        families.first() shouldBe CatalogFamily("flint", setOf("tools", "thinking"), listOf("40b"))
        CandidatePicker.newFamilies(EvolutionConfig(families = listOf("beam")), families) shouldBe listOf("flint", "mistral-large-4")
    }

    test("후보: 크기별로 양자화 순서대로 하나, 지금 모델·cloud·너무 작거나 큰 것·이미 본 것은 빼고, 같은 계열·큰 것 먼저") {
        val gb = 1_000_000_000L
        val tags = mapOf(
            "gemma4" to listOf(
                CatalogTag("gemma4:26b-mxfp8", "a1", 29 * gb),
                CatalogTag("gemma4:31b-nvfp4", "b2", 22 * gb),
                CatalogTag("gemma4:31b-mxfp8", "b1", 33 * gb),
                CatalogTag("gemma4:31b-cloud", "c1", 0),
                CatalogTag("gemma4:12b-mxfp8", "d1", 13 * gb),
                CatalogTag("gemma4:e2b-mxfp8", "e1", 5 * gb),
            ),
            "flint" to listOf(CatalogTag("flint:40b-q8_0", "f1", 43 * gb), CatalogTag("flint:120b-mxfp8", "g1", 130 * gb)),
        )
        val config = EvolutionConfig(maxCandidates = 3)
        CandidatePicker.pick(config, "gemma4:26b-mxfp8", tags) { false }.map { it.name } shouldContainExactly
            listOf("gemma4:31b-mxfp8", "gemma4:12b-mxfp8", "flint:40b-q8_0")
        CandidatePicker.pick(config, "gemma4:26b-mxfp8", tags) { it.digest == "b1" }.map { it.name } shouldContainExactly
            listOf("gemma4:12b-mxfp8", "flint:40b-q8_0")
    }

    test("게이트: 종합 점수가 올라도 한 항목이 떨어지거나 느리면 탈락") {
        val base = Scorecard("base", routing = 0.95, answers = 0.70, grounding = 0.90, korean = 1.0, toolJson = 1.0, emptyAnswers = 0, firstAnswerMs = 1_000, tokensPerSecond = 100.0, memoryBytes = null)
        val config = EvolutionConfig()
        Gate.judge(base, base.copy(model = "c", answers = 0.80), config).passed shouldBe true
        Gate.judge(base, base.copy(model = "c", answers = 0.71), config).reasons.single() shouldContain "종합 점수"
        Gate.judge(base, base.copy(model = "c", answers = 0.90, routing = 0.85), config).reasons.single() shouldContain "라우팅 하락"
        Gate.judge(base, base.copy(model = "c", answers = 0.90, firstAnswerMs = 1_600), config).reasons.single() shouldContain "느림"
        Gate.judge(base, base.copy(model = "c", answers = 0.90, emptyAnswers = 1), config).reasons.single() shouldContain "빈 답변"
        // 근거율은 없으면 나머지 비중으로
        base.copy(grounding = null).composite shouldBe ((0.70 * 0.4 + 0.95 * 0.2 + 1.0 * 0.15 + 1.0 * 0.1) / 0.85 plusOrMinus 1e-9)
    }

    test("라우터 임계값: 맞추는 쪽에서 오르고 확인용에서 안 떨어질 때만 바꾼다") {
        // 결정 모델은 신뢰도 0.5~0.6 구간에서도 맞고, 메인 모델은 틀린다 → 임계값을 0.5로 내리면 좋아진다
        val samples = (1..60).map { i ->
            val conf = listOf(0.52, 0.57, 0.8)[i % 3]
            RouteSample("질문 $i", AgentType.WEB, AgentType.WEB, conf, if (conf < 0.6) AgentType.CHAT else AgentType.WEB)
        }
        val tuned = ThresholdTuner.agent(samples, current = 0.6)
        tuned.changed shouldBe true
        tuned.value shouldBe 0.5
        tuned.holdoutAfter shouldBe 1.0
        // 정확도가 같으면 그대로 (메인 모델 판정이 줄어도 낮은 임계값은 처음 보는 질문에서 더 틀릴 수 있다)
        ThresholdTuner.agent(samples.map { it.copy(llm = AgentType.WEB) }, current = 0.6).changed shouldBe false
    }

    test("D6 임계값: 잘 갈리면 그대로, 겹치면 가르는 값으로") {
        val answerable = (1..20).map { "있는 $it" to 0.3 + it * 0.01 }
        val absent = (1..9).map { "없는 $it" to 0.05 + it * 0.005 }
        ThresholdTuner.codeSufficient(answerable, absent, current = 0.2).changed shouldBe false
        // 없는 질문이 0.25까지 올라오면 0.2는 틀린다
        val higher = absent.map { it.first to it.second + 0.15 }
        val tuned = ThresholdTuner.codeSufficient(answerable, higher, current = 0.2)
        tuned.changed shouldBe true
        (tuned.value in 0.245..0.31) shouldBe true
    }

    test("제안: 승인하면 tuned.properties에 쓰고, 되돌리면 이전 값으로. 두 번 승인·기한 지난 승인은 안 됨") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val dir = createTempDirectory("vesta")
        val tuned = dir.resolve("tuned.properties")
        var now = Instant.parse("2026-10-25T03:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val repo = ExposedEvolutionRepository(db.db, clock)
        val service = EvolutionService(repo, tuned, Duration.ofDays(7), clock)

        val tuning = service.applyNow(null, ProposalKind.TUNING, "임계값", "근거", mapOf("decision.thresholds.agent" to "0.55"))
        tuning.status shouldBe ProposalStatus.APPLIED
        TunedSettings.read(tuned) shouldBe mapOf("decision.thresholds.agent" to "0.55")

        val first = service.propose(1, ProposalKind.MODEL, "메인 모델 교체", "표", mapOf("models.general.name" to "gemma4:31b-mxfp8"))
        val second = service.propose(2, ProposalKind.MODEL, "메인 모델 교체", "표", mapOf("models.general.name" to "gemma4:31b-mxfp8", "models.code.name" to "gemma4:31b-mxfp8"))
        repo.proposal(first.id)!!.status shouldBe ProposalStatus.EXPIRED
        service.approve(first.id).ok shouldBe false

        service.approve(second.id).ok shouldBe true
        service.approve(second.id).ok shouldBe false
        TunedSettings.read(tuned)["models.general.name"] shouldBe "gemma4:31b-mxfp8"

        // 나중 변경이 남아 있으면 먼저 것을 되돌릴 수 없다… 키가 다르면 된다
        service.rollback(tuning.id).ok shouldBe true
        TunedSettings.read(tuned) shouldBe mapOf("models.general.name" to "gemma4:31b-mxfp8", "models.code.name" to "gemma4:31b-mxfp8")
        service.rollback(second.id).ok shouldBe true
        TunedSettings.read(tuned) shouldBe emptyMap()

        val late = service.propose(3, ProposalKind.MODEL, "메인 모델 교체", "표", mapOf("models.general.name" to "x:1"))
        now = now.plus(Duration.ofDays(8))
        service.approve(late.id).message shouldContain "만료"
        TunedSettings.read(tuned) shouldBe emptyMap()

        val conflict = service.propose(4, ProposalKind.MODEL, "메인 모델 교체", "표", mapOf("models.general.name" to "y:1"))
        TunedSettings.update(tuned, mapOf("models.general.name" to "z:1"))
        service.approve(conflict.id).ok shouldBe false
        TunedSettings.read(tuned)["models.general.name"] shouldBe "z:1"
    }
})
