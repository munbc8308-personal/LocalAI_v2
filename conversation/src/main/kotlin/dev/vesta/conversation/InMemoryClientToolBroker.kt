package dev.vesta.conversation

import dev.vesta.core.UserContext
import dev.vesta.core.tools.ClientToolBroker
import dev.vesta.core.tools.PendingClientTool
import dev.vesta.core.tools.ToolResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 다른 PC의 CLI에 맡긴 도구 실행 중계 (RC-07). 요청을 연 사용자 본인만 결과를 돌려줄 수 있다 */
class InMemoryClientToolBroker : ClientToolBroker {
    private val pending = ConcurrentHashMap<String, PendingClientTool>()

    override fun open(owner: UserContext): PendingClientTool =
        PendingClientTool(UUID.randomUUID().toString(), owner).also { pending[it.id] = it }

    override fun resolve(user: UserContext, id: String, result: ToolResult): Boolean {
        val request = pending[id] ?: return false
        if (request.owner.userId != user.userId) return false
        return request.result.complete(result).also { pending.remove(id) }
    }

    override fun close(id: String) {
        pending.remove(id)?.result?.complete(ToolResult("오류: 요청이 닫힘", isError = true))
    }
}
