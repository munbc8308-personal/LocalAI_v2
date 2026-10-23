package dev.vesta.app

import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.media.JobResult
import dev.vesta.core.media.JobStatus
import dev.vesta.core.media.MediaAsset
import dev.vesta.core.media.MediaKind
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.Session
import dev.vesta.core.session.StoredMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Instant

private class Messages : MessageRepository {
    val saved = mutableListOf<NewMessage>()
    override suspend fun append(sessionId: SessionId, message: NewMessage): StoredMessage {
        saved += message
        return StoredMessage(saved.size.toLong(), sessionId, message.role, message.content, message.agent, message.model, false, Instant.EPOCH)
    }
    override suspend fun list(sessionId: SessionId) = emptyList<StoredMessage>()
}

class JobDeskTest : FunSpec({
    val owner = UserContext(UserId(1), UserRole.OWNER)
    val session = Session(SessionId("s1"), owner.userId, "t", ChannelType.TELEGRAM, null, Instant.EPOCH, Instant.EPOCH)

    test("차례대로 돌고, 결과는 그 대화에 답 메시지(첨부)로 남기고 알린다. 다른 사용자는 못 본다. 실패하면 실패 답") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val messages = Messages()
            val desk = JobDesk(messages, scope)
            val finished = mutableListOf<Pair<String, JobStatus>>()
            val all = CompletableDeferred<Unit>()
            desk.onFinished { job, s, _ -> finished += job.title to job.status; s.id.value shouldBe "s1"; if (finished.size == 2) all.complete(Unit) }
            val gate = CompletableDeferred<Unit>()
            var concurrent = 0
            var maxConcurrent = 0
            val asset = MediaAsset("v1", owner.userId, MediaKind.VIDEO, "video/mp4", 3)
            val first = desk.submit(owner, session, "가을 산책", AgentType.VIDEO) { progress ->
                concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent)
                progress("장면 1/3 그리는 중")
                gate.await()
                concurrent--
                JobResult(asset, byteArrayOf(1, 2, 3), "영상을 만들었습니다: 가을 산책")
            }
            val second = desk.submit(owner, session, "바다", AgentType.VIDEO) { _ ->
                concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent); concurrent--
                error("메모리 부족")
            }
            second.progress shouldBe "앞 작업 1개를 기다리는 중"
            withTimeout(5_000) { while (desk.get(owner.userId, first.id)?.progress != "장면 1/3 그리는 중") delay(10) }
            desk.busy shouldBe true
            desk.get(UserId(2), first.id) shouldBe null
            desk.get(owner.userId, second.id)!!.status shouldBe JobStatus.QUEUED
            gate.complete(Unit)
            withTimeout(5_000) { all.await() }
            maxConcurrent shouldBe 1
            finished shouldBe listOf("가을 산책" to JobStatus.DONE, "바다" to JobStatus.FAILED)
            desk.get(owner.userId, first.id)!!.let { it.mediaId shouldBe "v1"; it.mime shouldBe "video/mp4" }
            messages.saved[0].let { it.attachments shouldBe listOf("v1"); it.agent shouldBe AgentType.VIDEO; it.content shouldContain "가을 산책" }
            messages.saved[1].content shouldContain "만들지 못했습니다"
            desk.busy shouldBe false
        } finally {
            scope.cancel()
        }
    }
})
