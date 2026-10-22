package dev.vesta.channels

import dev.vesta.core.ChannelType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** 메시지 한 번 고치는 데 [latency]ms 걸리는 메신저 */
private class SlowSurface(private val latency: Long) : ChatSurface {
    val edits = mutableListOf<String>()
    override val channel = ChannelType.TELEGRAM
    override val name = "Slow"
    override val key = "slow"
    override val commandPrefix = '/'
    override val partLimit = 4_000
    override val messageLimit = 4_096
    override val approvalBodyLimit = 3_000
    override suspend fun sendText(chat: String, text: String) = "1"
    override suspend fun editText(chat: String, messageId: String, text: String) = Unit
    override suspend fun post(chat: String, content: String, formatted: Boolean): String { delay(latency); edits += content; return "1" }
    override suspend fun edit(chat: String, messageId: String, content: String, formatted: Boolean) { delay(latency); edits += content }
    override fun render(markdown: String) = markdown
    override fun renderFooter(footer: ReplyFooter) = ""
    override suspend fun sendButtons(chat: String, text: String, rows: List<List<ChannelButton>>) = "1"
    override suspend fun sendApproval(chat: String, card: ApprovalCard, buttons: List<ChannelButton>) = "1"
    override suspend fun closeApproval(chat: String, messageId: String, card: ApprovalCard, result: String, edited: String?) = Unit
    override suspend fun askEdit(chat: String, current: String) = Unit
}

class LiveReplyTest : FunSpec({
    test("메신저가 느려도 생성(글자 받기)은 기다리지 않고, 끝나면 마지막 내용으로 고친다") {
        val surface = SlowSurface(latency = 300)
        var spent = 0L
        coroutineScope {
            val reply = LiveReply(surface, "c", intervalMillis = 100, nowMillis = { System.nanoTime() / 1_000_000 }, scope = this)
            reply.start("생각 중…")
            val started = System.nanoTime()
            // 토큰 100개를 10ms마다 (생성 1초)
            repeat(100) { reply.append("가"); delay(10) }
            spent = (System.nanoTime() - started) / 1_000_000
            reply.finish("가".repeat(100), null)
        }
        // 기다렸다면 수정 횟수 × 300ms만큼 늦어진다
        spent shouldBeLessThan 2_000L
        surface.edits.last() shouldBe "가".repeat(100)
        // 수정은 간격을 지켜 몇 번만 (토큰마다가 아니라)
        surface.edits.size shouldBeLessThan 10
    }
})
