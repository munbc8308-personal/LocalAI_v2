package dev.vesta.identity

import dev.vesta.core.UserId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 요청 큐 (PROJECT.md U-05). 메인 모델은 하나라 동시에 [slots]개까지만 생성하고, 나머지는 기다린다.
 * 자리가 나면 사용자별로 돌아가며(라운드 로빈) 꺼낸다: 한 사람이 질문을 여러 개 보내도 다른 사람이 계속 밀리지 않는다.
 */
class FairScheduler(private val slots: Int) {
    private val mutex = Mutex()
    private var running = 0
    /** 사용자별 대기열. 순서가 곧 라운드 로빈 차례 */
    private val waiting = LinkedHashMap<UserId, ArrayDeque<CompletableDeferred<Unit>>>()

    init {
        require(slots >= 1) { "slots must be >= 1" }
    }

    /**
     * 차례가 오면 [block]을 실행한다. 기다려야 하면 먼저 [onWait]에 앞에 있는 요청 수를 알려 준다.
     * 기다리는 중에 취소되면 대기열에서 빠진다.
     */
    suspend fun <T> run(user: UserId, onWait: suspend (ahead: Int) -> Unit = {}, block: suspend () -> T): T {
        val ticket = CompletableDeferred<Unit>()
        val ahead = mutex.withLock {
            if (running < slots && waiting.isEmpty()) {
                running++
                ticket.complete(Unit)
                0
            } else {
                // 라운드 로빈이라 정확한 순번은 아니고, 지금 기다리는 요청 수를 알려 준다
                val count = waiting.values.sumOf { it.size }
                waiting.getOrPut(user) { ArrayDeque() }.addLast(ticket)
                count + 1
            }
        }
        if (ahead > 0) onWait(ahead)
        try {
            ticket.await()
        } catch (e: Throwable) {
            mutex.withLock {
                val removed = waiting[user]?.remove(ticket) == true
                if (waiting[user]?.isEmpty() == true) waiting.remove(user)
                // 자리를 받은 직후에 취소됐으면 자리를 돌려준다
                if (!removed && ticket.isCompleted) releaseLocked()
            }
            throw e
        }
        try {
            return block()
        } finally {
            mutex.withLock { releaseLocked() }
        }
    }

    /** 지금 생성 중인 요청 수와 기다리는 요청 수 */
    suspend fun load(): Pair<Int, Int> = mutex.withLock { running to waiting.values.sumOf { it.size } }

    private fun releaseLocked() {
        running--
        // 맨 앞 사용자의 요청 하나를 깨우고, 그 사용자를 맨 뒤로 보낸다
        val next = waiting.keys.firstOrNull() ?: return
        val queue = waiting.remove(next)!!
        val ticket = queue.removeFirst()
        if (queue.isNotEmpty()) waiting[next] = queue
        running++
        ticket.complete(Unit)
    }
}
