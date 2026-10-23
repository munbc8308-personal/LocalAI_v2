package dev.vesta.storage

import dev.vesta.core.ModelRole
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.studio.CustomAgentDef
import dev.vesta.core.studio.RunStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory

class ExposedStudioRepositoriesTest : FunSpec({
    test("사용자 정의 에이전트: 사용자마다 따로, 같은 이름이면 고친다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "mina"; it[role] = UserRole.MEMBER } }.value.let(::UserId)
        val repo = ExposedCustomAgentRepository(db.db)
        repo.save(CustomAgentDef(owner, "reviewer", "코드 리뷰어", "문제만 짚는다", ModelRole.CODE, listOf("read_file")))
        repo.save(CustomAgentDef(other, "reviewer", "남의 것", ""))
        repo.save(CustomAgentDef(owner, "reviewer", "코드 리뷰어 2", "고친 지침", ModelRole.CODE, listOf("read_file", "list_dir")))
        repo.list(owner).single().let {
            it.description shouldBe "코드 리뷰어 2"
            it.tools shouldBe listOf("read_file", "list_dir")
            it.model shouldBe ModelRole.CODE
        }
        repo.find(other, "reviewer")?.description shouldBe "남의 것"
        repo.delete(other, "reviewer") shouldBe true
        repo.find(owner, "reviewer")?.instructions shouldBe "고친 지침"
        repo.list(other).shouldBeEmpty()
    }

    test("실행 기록: 단계 번호는 실행마다 1부터, 끝나지 못한 실행은 실패로 정리") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val runs = ExposedRunRepository(db.db)
        val a = runs.start(owner, null, "CHAT", null, "질문 하나")
        val b = runs.start(owner, null, "CHAT", null, "질문 둘")
        runs.add(a.id, "input", "질문 하나", null).seq shouldBe 1
        runs.add(a.id, "routed", "작업", null).seq shouldBe 2
        runs.add(b.id, "input", "질문 둘", null).seq shouldBe 1
        runs.setAgent(a.id, "TASK", "reviewer")
        runs.finish(a.id, RunStatus.DONE, "답")
        runs.get(a.id)!!.let {
            it.status shouldBe RunStatus.DONE
            it.customAgent shouldBe "reviewer"
            it.answer shouldBe "답"
            (it.finishedAt != null) shouldBe true
        }
        runs.events(a.id).map { it.kind } shouldBe listOf("input", "routed")
        runs.events(a.id, afterSeq = 1).map { it.kind } shouldBe listOf("routed")
        runs.list(owner, 10).map { it.id } shouldBe listOf(b.id, a.id)
        runs.abandonRunning() shouldBe 1
        runs.get(b.id)!!.status shouldBe RunStatus.FAILED
    }

    test("팀: 같은 이름이면 고치고, 멤버 순서를 지킨다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val teams = ExposedTeamRepository(db.db)
        teams.save(dev.vesta.core.studio.TeamDef(owner, "dev", "기획 → 개발", "planner", listOf("coder")))
        teams.save(dev.vesta.core.studio.TeamDef(owner, "dev", "기획 → 개발 → 리뷰", "planner", listOf("coder", "reviewer")))
        teams.list(owner).single().let { it.description shouldBe "기획 → 개발 → 리뷰"; it.members shouldBe listOf("coder", "reviewer") }
        teams.delete(owner, "dev") shouldBe true
        teams.find(owner, "dev") shouldBe null
    }

    test("웹 스킬: 사용자마다, 같은 이름이면 바꾼다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val repo = ExposedSkillRepository(db.db)
        repo.save(owner, dev.vesta.core.skill.Skill("요약", "요약", "세 줄"))
        repo.save(owner, dev.vesta.core.skill.Skill("요약", "요약 2", "다섯 줄", dev.vesta.core.AgentType.TASK, listOf("read_file")))
        repo.list(owner).single().let { it.body shouldBe "다섯 줄"; it.agent shouldBe dev.vesta.core.AgentType.TASK; it.tools shouldBe listOf("read_file") }
        repo.delete(owner, "요약") shouldBe true
    }

    test("학습 재료 (M25): 중복 제외, 피드백은 답마다 하나로 고침, 검토·개수·동의한 사용자 것만, 커서") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "mina"; it[role] = UserRole.MEMBER } }.value.let(::UserId)
        val repo = ExposedTrainingSampleRepository(db.db)
        fun sample(user: UserId, kind: dev.vesta.core.training.SampleKind, signal: dev.vesta.core.training.SampleSignal, q: String, label: String, status: dev.vesta.core.training.SampleStatus, message: Long? = null) =
            dev.vesta.core.training.TrainingSample(0, user, kind, signal, message, q, null, label, 0.8, status)
        val r = repo.add(sample(owner, dev.vesta.core.training.SampleKind.ROUTING, dev.vesta.core.training.SampleSignal.REASK, "오늘 날씨", "WEB", dev.vesta.core.training.SampleStatus.PENDING))!!
        repo.add(sample(owner, dev.vesta.core.training.SampleKind.ROUTING, dev.vesta.core.training.SampleSignal.REASK, "오늘 날씨?", "WEB", dev.vesta.core.training.SampleStatus.PENDING)) shouldBe null
        repo.add(sample(owner, dev.vesta.core.training.SampleKind.ANSWER, dev.vesta.core.training.SampleSignal.FEEDBACK, "q", "good", dev.vesta.core.training.SampleStatus.ACCEPTED, message = 9))!!
        repo.add(sample(owner, dev.vesta.core.training.SampleKind.ANSWER, dev.vesta.core.training.SampleSignal.FEEDBACK, "q", "bad", dev.vesta.core.training.SampleStatus.ACCEPTED, message = 9))!!
        repo.list(null, 10).single { it.signal == dev.vesta.core.training.SampleSignal.FEEDBACK }.label shouldBe "bad"
        repo.add(sample(other, dev.vesta.core.training.SampleKind.ROUTING, dev.vesta.core.training.SampleSignal.REASK, "회의록", "DOCS", dev.vesta.core.training.SampleStatus.ACCEPTED))
        repo.counts() shouldBe mapOf("ROUTING:PENDING" to 1, "ANSWER:ACCEPTED" to 1, "ROUTING:ACCEPTED" to 1)
        repo.review(r.id, dev.vesta.core.training.SampleStatus.ACCEPTED, "DOCS") shouldBe true
        repo.accepted(dev.vesta.core.training.SampleKind.ROUTING, setOf(owner)).map { it.question to it.label } shouldBe listOf("오늘 날씨" to "DOCS")
        repo.cursor() shouldBe 0
        repo.setCursor(42); repo.setCursor(43)
        repo.cursor() shouldBe 43
    }

    test("큐레이터 턴: 질문·답·라우팅 단계·거부한 도구를 묶고, 새 질문 앞 턴부터, 동의한 사용자 것만") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val user = dev.vesta.core.UserContext(owner, UserRole.OWNER)
        val sessions = ExposedSessionRepository(db.db)
        val messages = ExposedMessageRepository(db.db)
        val session = sessions.create(owner, dev.vesta.core.ChannelType.WEB, "t")
        fun pause() = Thread.sleep(15)
        val q1 = messages.append(session.id, dev.vesta.core.session.NewMessage(dev.vesta.core.inference.ChatRole.USER, "오늘 날씨"))
        pause()
        ExposedRoutingLogRepository(db.db).record(session.id, dev.vesta.core.decision.RoutingDecision(dev.vesta.core.AgentType.CHAT, 0.7, dev.vesta.core.decision.RoutingStage.DECISION_MODEL, question = "오늘 날씨"))
        val a1 = messages.append(session.id, dev.vesta.core.session.NewMessage(dev.vesta.core.inference.ChatRole.ASSISTANT, "모릅니다", agent = dev.vesta.core.AgentType.CHAT))
        pause()
        val q2 = messages.append(session.id, dev.vesta.core.session.NewMessage(dev.vesta.core.inference.ChatRole.USER, "/task 파일 지워"))
        pause()
        ExposedToolRunLog(db.db).record(user, session.id, "run_shell", "{}", dev.vesta.core.RiskLevel.EXEC, false, dev.vesta.core.tools.ToolResult("거부"), 0)
        val source = ExposedCuratorSource(db.db)
        val turns = source.turns(q1.id, setOf(owner), 100)
        turns.map { it.questionId } shouldBe listOf(q1.id, q2.id)
        turns[0].let { it.answerId shouldBe a1.id; it.answerAgent shouldBe "CHAT"; it.routedStage shouldBe "DECISION_MODEL"; it.deniedTools shouldBe emptyList() }
        turns[1].deniedTools shouldBe listOf("run_shell")
        source.turns(q2.id, setOf(owner), 100) shouldBe emptyList()
        source.turns(0, emptySet(), 100) shouldBe emptyList()
        source.turnOfAnswer(a1.id)!!.question shouldBe "오늘 날씨"
        source.turnOfAnswer(q1.id) shouldBe null
    }
})
