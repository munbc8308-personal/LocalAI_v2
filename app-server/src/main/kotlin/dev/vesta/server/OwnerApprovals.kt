package dev.vesta.server

import dev.vesta.channels.ButtonStyle
import dev.vesta.channels.ChannelActions
import dev.vesta.channels.ChannelButton
import dev.vesta.channels.ChannelNotifier
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import dev.vesta.core.tools.ApprovalEscalation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * 셸 승인 넘기기 (PROJECT.md 7.12, M20): Mac 주인이 아닌 사용자의 셸 요청을 주인의 메신저로 보내 [승인]/[거부]를 받는다.
 * - 카드에는 누가·무엇을(명령 전체·작업 폴더·이유·D8 경고) 보여 준다. 다 보여 줄 수 없게 길면 묻지 않고 거부
 * - 버튼은 Mac 주인만 누를 수 있고, 한 번 처리하면 끝 (여러 메신저에 같은 카드가 가도 처음 누른 것만)
 * - 주인과 연결된 메신저가 없거나 [ask]의 시간이 지나면 거부
 */
class OwnerApprovals(
    private val owner: UserId,
    private val notifiers: () -> List<ChannelNotifier>,
    private val userName: suspend (UserId) -> String?,
    private val bodyLimit: Int = 1_200,
) : ApprovalEscalation, ChannelActions {
    override val prefix = "sh"
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()
    private val random = SecureRandom()

    override suspend fun ask(requester: UserContext, tool: String, details: ApprovalDetails, reason: String?, timeoutSeconds: Long): ApprovalDecision {
        if (details.body.length > bodyLimit) return ApprovalDecision.Deny("명령이 너무 길어 Mac 주인에게 전부 보여 줄 수 없습니다")
        val id = (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789"[random.nextInt(31)] }.joinToString("")
        val result = CompletableDeferred<ApprovalDecision>()
        pending[id] = result
        try {
            val who = userName(requester.userId) ?: "사용자 ${requester.userId.value}"
            val text = buildString {
                append("$who 님이 셸 실행 승인을 요청했습니다\n\n")
                append(details.title).append('\n')
                if (details.body.isNotBlank()) append(details.body).append('\n')
                reason?.takeIf { it.isNotBlank() }?.let { append("이유: ").append(it.take(300)).append('\n') }
                details.warning?.let { append("⚠ ").append(it).append('\n') }
                append("\n${timeoutSeconds / 60}분 안에 답하지 않으면 거부합니다")
            }
            val buttons = listOf(ChannelButton("승인", "sh:$id:a", ButtonStyle.PRIMARY), ChannelButton("거부", "sh:$id:d", ButtonStyle.DANGER))
            val sent = notifiers().sumOf { runCatching { it.notify(owner, "sh:$id", text, buttons) }.getOrDefault(0) }
            if (sent == 0) return ApprovalDecision.Deny("Mac 주인에게 승인을 물을 메신저가 없습니다 (주인 계정을 메신저에 연결하세요)")
            log.info { "셸 승인 넘김: $who → 주인 (${details.title.take(80)})" }
            return withTimeoutOrNull(timeoutSeconds.seconds) { result.await() } ?: ApprovalDecision.Deny("Mac 주인이 ${timeoutSeconds / 60}분 동안 답하지 않아 거부")
        } finally {
            pending.remove(id)
        }
    }

    /** `sh:<번호>:<a 승인|d 거부>`. Mac 주인만 */
    override suspend fun onButton(user: UserContext, data: String): String {
        if (user.role != UserRole.OWNER || user.userId != owner) return "Mac 주인만 승인할 수 있습니다"
        val parts = data.split(':')
        val request = pending[parts.getOrNull(1).orEmpty()] ?: return "이미 끝난 요청입니다"
        val decision = if (parts.getOrNull(2) == "a") ApprovalDecision.Approve else ApprovalDecision.Deny("Mac 주인이 거부함")
        return if (request.complete(decision)) (if (decision == ApprovalDecision.Approve) "승인했습니다" else "거부했습니다") else "이미 끝난 요청입니다"
    }
}
