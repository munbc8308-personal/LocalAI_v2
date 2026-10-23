package dev.vesta.app

import dev.vesta.core.AgentType
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.media.BackgroundJobs
import dev.vesta.core.media.JobResult
import dev.vesta.core.media.JobState
import dev.vesta.core.media.JobStatus
import dev.vesta.core.session.MessageRepository
import dev.vesta.core.session.NewMessage
import dev.vesta.core.session.Session
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * 백그라운드 작업 (M24): 영상처럼 몇 분 걸리는 일을 요청과 떼어 돌린다. 메모리를 많이 쓰는 일이라 한 번에 하나씩(차례대로).
 * 끝나면 결과를 그 대화에 답 메시지로 남기고(결과물 첨부, 다음 질문이 "영상을 만들었습니다"를 보고 고치기로 이어진다),
 * 등록한 [onFinished]로 알린다(서버: 요청한 메신저로 보내기). 상태는 메모리에만 둔다: 서버를 다시 켜면 진행 중이던 작업은 사라진다
 */
class JobDesk(private val messages: MessageRepository, private val scope: CoroutineScope, private val keep: Int = 200) : BackgroundJobs {
    private val jobs = ConcurrentHashMap<String, JobState>()
    private val order = CopyOnWriteArrayList<String>()
    private val lock = Mutex()
    private val listeners = CopyOnWriteArrayList<suspend (JobState, Session, JobResult?) -> Unit>()

    /** 기다리거나 도는 작업이 있다 (서버 다시 시작·큐레이터는 이때 하지 않는다) */
    val busy: Boolean get() = jobs.values.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }

    /** 작업이 끝났을 때 (실패 포함, 실패면 결과가 null) */
    fun onFinished(listener: suspend (JobState, Session, JobResult?) -> Unit) { listeners += listener }

    override fun submit(user: UserContext, session: Session, title: String, agent: AgentType, work: suspend (suspend (String) -> Unit) -> JobResult): JobState {
        val id = UUID.randomUUID().toString()
        val ahead = jobs.values.count { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
        val queued = JobState(id, user.userId, session.id.value, title, JobStatus.QUEUED, if (ahead > 0) "앞 작업 ${ahead}개를 기다리는 중" else null)
        jobs[id] = queued
        order += id
        trim()
        scope.launch {
            lock.withLock {
                update(id) { it.copy(status = JobStatus.RUNNING, progress = "시작") }
                val result = try {
                    work { text -> update(id) { it.copy(progress = text) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "백그라운드 작업 실패: $title" }
                    null
                }
                val text = result?.text ?: "\"$title\"을(를) 만들지 못했습니다. 다시 요청해 주세요."
                val saved = runCatching {
                    messages.append(session.id, NewMessage(ChatRole.ASSISTANT, text, agent, attachments = listOfNotNull(result?.media?.id)))
                }.onFailure { log.warn(it) { "작업 결과 저장 실패" } }
                val done = update(id) {
                    it.copy(
                        status = if (result?.media != null) JobStatus.DONE else JobStatus.FAILED,
                        progress = null, mediaId = result?.media?.id, mime = result?.media?.mime, text = text,
                    )
                }
                if (saved.isSuccess) listeners.forEach { listener ->
                    runCatching { listener(done, session, result) }.onFailure { log.warn(it) { "작업 알림 실패" } }
                }
            }
        }
        return queued
    }

    override fun get(userId: UserId, id: String): JobState? = jobs[id]?.takeIf { it.userId == userId }

    private fun update(id: String, change: (JobState) -> JobState): JobState = jobs.compute(id) { _, old -> old?.let(change) }!!

    private fun trim() {
        while (order.size > keep) {
            val oldest = order.firstOrNull { jobs[it]?.status == JobStatus.DONE || jobs[it]?.status == JobStatus.FAILED } ?: break
            order.remove(oldest)
            jobs.remove(oldest)
        }
    }
}
