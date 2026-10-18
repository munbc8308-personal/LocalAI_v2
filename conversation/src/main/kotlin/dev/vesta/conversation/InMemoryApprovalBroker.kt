package dev.vesta.conversation

import dev.vesta.core.UserContext
import dev.vesta.core.tools.ApprovalBroker
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.PendingApproval
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 프로세스 안 승인 중계기. 요청을 연 사용자 본인만 답할 수 있다 (PROJECT.md 6.6). */
class InMemoryApprovalBroker : ApprovalBroker {
    private val pending = ConcurrentHashMap<String, PendingApproval>()

    override fun open(owner: UserContext): PendingApproval =
        PendingApproval(UUID.randomUUID().toString().take(8), owner).also { pending[it.id] = it }

    override fun resolve(user: UserContext, id: String, decision: ApprovalDecision): Boolean {
        val request = pending[id] ?: return false
        if (request.owner.userId != user.userId) return false
        return request.result.complete(decision).also { pending.remove(id) }
    }

    override fun close(id: String) {
        pending.remove(id)?.result?.complete(ApprovalDecision.Deny("요청이 닫힘"))
    }
}
