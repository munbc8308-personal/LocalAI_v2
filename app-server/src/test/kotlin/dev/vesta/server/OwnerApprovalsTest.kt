package dev.vesta.server

import dev.vesta.channels.ChannelButton
import dev.vesta.channels.ChannelNotifier
import dev.vesta.core.ChannelType
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.UserRole
import dev.vesta.core.tools.ApprovalDecision
import dev.vesta.core.tools.ApprovalDetails
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class OwnerApprovalsTest : FunSpec({
    val owner = UserId(1)
    val member = UserContext(UserId(2), UserRole.MEMBER)
    class Card(val user: UserId, val text: String, val buttons: List<ChannelButton>)

    test("셸 승인 넘기기: 주인 메신저로 카드(누가·명령), 주인만 누를 수 있고 한 번만") {
        val cards = mutableListOf<Card>()
        val notifier = object : ChannelNotifier {
            override val name = "가짜"
            override val channel = ChannelType.TELEGRAM
            override suspend fun notify(user: UserId, key: String, text: String, buttons: List<ChannelButton>, exact: Boolean): Int { cards += Card(user, text, buttons); return 1 }
        }
        val approvals = OwnerApprovals(owner, { listOf(notifier) }, { if (it == member.userId) "mina" else null })
        coroutineScope {
            val result = async { approvals.ask(member, "run_shell", ApprovalDetails("셸 실행: ls -la", "명령: ls -la\n작업 폴더: ~/Vesta/users/mina"), "목록 확인", 60) }
            while (cards.isEmpty()) delay(10)
            val card = cards.single()
            card.user shouldBe owner
            card.text shouldContain "mina 님이 셸 실행 승인을 요청했습니다"
            card.text shouldContain "명령: ls -la"
            card.text shouldContain "이유: 목록 확인"
            val approve = card.buttons.first().data
            approvals.onButton(member, approve) shouldContain "Mac 주인만"
            approvals.onButton(UserContext(owner, UserRole.OWNER), approve) shouldBe "승인했습니다"
            result.await() shouldBe ApprovalDecision.Approve
            approvals.onButton(UserContext(owner, UserRole.OWNER), approve) shouldContain "이미 끝난"
        }
    }

    test("메신저가 없거나, 시간이 지나거나, 명령이 너무 길면 거부") {
        val none = OwnerApprovals(owner, { emptyList() }, { "mina" })
        none.ask(member, "run_shell", ApprovalDetails("셸", "ls"), null, 60).shouldBeInstanceOf<ApprovalDecision.Deny>().reason shouldContain "보내지 못했습니다"
        val silent = object : ChannelNotifier {
            override val name = "가짜"
            override val channel = ChannelType.TELEGRAM
            override suspend fun notify(user: UserId, key: String, text: String, buttons: List<ChannelButton>, exact: Boolean) = 1
        }
        OwnerApprovals(owner, { listOf(silent) }, { "mina" }).ask(member, "run_shell", ApprovalDetails("셸", "ls"), null, 1)
            .shouldBeInstanceOf<ApprovalDecision.Deny>().reason shouldContain "답하지 않아"
        OwnerApprovals(owner, { listOf(silent) }, { "mina" }, bodyLimit = 10).ask(member, "run_shell", ApprovalDetails("셸", "x".repeat(50)), null, 60)
            .shouldBeInstanceOf<ApprovalDecision.Deny>().reason shouldContain "너무 길어"
    }
})
