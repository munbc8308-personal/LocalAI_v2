package dev.vesta.storage

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.UserId
import dev.vesta.core.decision.RoutingDecision
import dev.vesta.core.decision.RoutingStage
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.NewMessage
import dev.vesta.core.training.Feedback
import dev.vesta.core.training.TrainingStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.createTempDirectory

class ExposedTrainingRepositoriesTest : FunSpec({
    test("피드백은 자기 답변에만, 라우팅 정정은 앞 질문과 함께 학습 후보가 되고 routing_log에도 남는다") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val owner = db.ensureOwner("owner")
        val other = transaction(db.db) { Users.insertAndGetId { it[name] = "guest"; it[role] = dev.vesta.core.UserRole.GUEST } }.value.let(::UserId)
        val sessions = ExposedSessionRepository(db.db)
        val messages = ExposedMessageRepository(db.db)
        val session = sessions.create(owner, ChannelType.WEB, "t")
        val question = messages.append(session.id, NewMessage(ChatRole.USER, "다운로드 폴더 정리해 줘"))
        val answer = messages.append(session.id, NewMessage(ChatRole.ASSISTANT, "문서에서 찾지 못했습니다", agent = AgentType.DOCS))
        ExposedRoutingLogRepository(db.db).record(session.id, RoutingDecision(AgentType.DOCS, 0.9, RoutingStage.DECISION_MODEL, question = "다운로드 폴더 정리해 줘"))
        val repo = ExposedFeedbackRepository(db.db)

        repo.save(other, Feedback(answer.id, -1, AgentType.TASK)) shouldBe false
        repo.save(owner, Feedback(question.id, 1)) shouldBe false // 질문에는 남기지 않는다
        repo.save(owner, Feedback(answer.id, -1, AgentType.TASK)) shouldBe true
        repo.find(owner, answer.id)?.correctAgent shouldBe AgentType.TASK
        repo.find(other, answer.id) shouldBe null
        repo.routingCorrections(owner).single().let {
            it.question shouldBe "다운로드 폴더 정리해 줘"
            it.agent shouldBe AgentType.TASK
        }
        repo.routingCorrections(other).shouldBeEmpty()
        transaction(db.db) { RoutingLogs.selectAll().single()[RoutingLogs.userOverride] } shouldBe "TASK"
        // 다시 남기면 바꾼다 (정정 취소)
        repo.save(owner, Feedback(answer.id, 1, null)) shouldBe true
        repo.routingCorrections(owner).shouldBeEmpty()
    }

    test("학습 기록과 버전: 하나만 켜지고, 기본 모델 이름으로 켜면 모두 꺼진다. 끝나지 못한 실행은 실패로 정리") {
        val db = VestaDatabase.open(createTempDirectory("vesta").resolve("vesta.db")).also { it.migrate() }
        val runs = ExposedTrainingRepository(db.db)
        val run = runs.start("decision", "winnow:e4b")
        runs.progress(run.id, "학습", 3, 10, 0.25)
        runs.get(run.id)!!.let { it.step shouldBe 3; it.loss shouldBe 0.25 }
        runs.abandonRunning() shouldBe 1
        runs.get(run.id)!!.status shouldBe TrainingStatus.FAILED

        val versions = ExposedModelVersionRepository(db.db)
        versions.add("decision", "winnow-vesta:r1", "winnow:e4b", 1, "{}")
        versions.add("decision", "winnow-vesta:r2", "winnow:e4b", 2, "{}")
        versions.activate("decision", "winnow-vesta:r1")
        versions.active("decision")?.name shouldBe "winnow-vesta:r1"
        versions.activate("decision", "winnow-vesta:r2")
        versions.list("decision").count { it.active } shouldBe 1
        versions.activate("decision", "winnow:e4b")
        versions.active("decision") shouldBe null
    }
})
