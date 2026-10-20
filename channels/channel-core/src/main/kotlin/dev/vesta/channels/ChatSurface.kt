package dev.vesta.channels

import dev.vesta.core.ChannelType
import java.io.IOException
import java.time.Instant

/**
 * 메신저 하나(Telegram·Discord·Slack)의 메시지 보내기·고치기와 서식 (PROJECT.md 6.5).
 * 대화·승인·파일 처리 순서는 [ChannelBot]이 정하고, 플랫폼은 이것만 구현한다. 메시지·대화방 ID는 문자열로 다룬다.
 */
interface ChatSurface {
    val channel: ChannelType
    /** 로그·거부 사유에 쓰는 이름 ("Telegram") */
    val name: String
    /** 설정 키 (`channels.<key>`) */
    val key: String
    /** 도움말에 쓰는 명령어 앞 글자. Slack은 `/`를 앱 명령으로 가로채므로 `!` */
    val commandPrefix: Char
    /** 답변 메시지 한 개에 넣을 원문 길이 (상태 줄·출처 자리를 남긴다) */
    val partLimit: Int
    /** 플랫폼의 메시지 한 개 최대 길이 */
    val messageLimit: Int
    /** 승인 카드에 전부 보여 줄 수 있는 실행 내용 길이 */
    val approvalBodyLimit: Int

    /** 서식 없는 글을 보낸다 */
    suspend fun sendText(chat: String, text: String): String

    /** 서식 없는 글로 고치고 버튼을 없앤다 */
    suspend fun editText(chat: String, messageId: String, text: String)

    /** [formatted]면 [render]·[renderFooter] 결과, 아니면 원문 그대로(플랫폼이 이스케이프) */
    suspend fun post(chat: String, content: String, formatted: Boolean): String
    suspend fun edit(chat: String, messageId: String, content: String, formatted: Boolean)

    /** 답변 Markdown 조각 → 플랫폼 서식 */
    fun render(markdown: String): String
    fun renderFooter(footer: ReplyFooter): String

    suspend fun sendButtons(chat: String, text: String, rows: List<List<ChannelButton>>): String
    suspend fun sendApproval(chat: String, card: ApprovalCard, buttons: List<ChannelButton>): String

    /** 승인 카드에 결과를 적고 버튼을 없앤다. [edited]는 수정해서 승인한 값 */
    suspend fun closeApproval(chat: String, messageId: String, card: ApprovalCard, result: String, edited: String?)

    /** 입력 창이 없는 플랫폼(Telegram): 수정한 값을 다음 메시지로 보내 달라고 한다 */
    suspend fun askEdit(chat: String, current: String)
}

enum class ButtonStyle { NORMAL, PRIMARY, DANGER }

/** [data]는 엔진이 정한 값 (`ap:<id>:a`, `fq:<key>` …) */
data class ChannelButton(val label: String, val data: String, val style: ButtonStyle = ButtonStyle.NORMAL)

/** 승인 요청 내용. [body]는 실행할 명령 전체 (PROJECT.md 6.6) */
data class ApprovalCard(
    val tool: String,
    val risk: String,
    val title: String,
    val body: String,
    val reason: String?,
    val warning: String?,
)

data class FooterSource(val number: Int, val label: String, val url: String?)

/** 답변 끝에 붙는 출처·검증·에이전트 이름 */
data class ReplyFooter(val sources: List<FooterSource>, val unsupported: Int, val agent: String?) {
    val isEmpty get() = sources.isEmpty() && unsupported == 0 && agent == null
}

/** 플랫폼에서 받은 일을 [ChannelBot]이 알아듣는 모양으로 바꾼 것 */
sealed interface Inbound {
    val userId: String
    val userName: String?

    /** 글 또는 파일. 파일이 있으면 [text]는 그 설명(캡션) */
    data class Message(
        override val userId: String,
        override val userName: String?,
        val chat: String,
        val text: String?,
        /** 1:1 대화인지. 아니면 받지 않는다 */
        val direct: Boolean = true,
        val sentAt: Instant? = null,
        val file: InboundFile? = null,
    ) : Inbound

    data class Button(
        override val userId: String,
        override val userName: String?,
        val chat: String?,
        val data: String,
        val reply: ButtonReply,
    ) : Inbound

    /** 플랫폼 입력 창(Discord 모달·Slack 모달)에서 수정한 승인 값 */
    data class EditSubmitted(
        override val userId: String,
        override val userName: String?,
        val requestId: String,
        val text: String,
    ) : Inbound
}

class InboundFile(val name: String, val size: Long?, val download: suspend (maxBytes: Long) -> ByteArray)

/** 버튼 누름에 대한 응답. 플랫폼마다 정해진 시간 안에 한 번 답해야 한다 */
interface ButtonReply {
    /**
     * [notice]는 누른 사람에게만 잠깐 보이는 안내. [minor]면 결과가 메시지에 이미 보이는 안내라,
     * 잠깐 뜨는 알림이 없는 플랫폼(Discord·Slack)은 보내지 않는다
     */
    suspend fun ack(notice: String? = null, minor: Boolean = false)

    /** 수정 입력 창을 연다(이것이 응답이 된다). 입력 창이 없는 플랫폼은 false */
    suspend fun openEditor(requestId: String, current: String): Boolean = false
}

/** 플랫폼 API 오류. [retryAfterMillis]가 있으면 그만큼 기다렸다 다시 보낸다 */
open class ChannelApiException(message: String, val retryAfterMillis: Long? = null) : IOException(message) {
    /** 같은 내용으로 고치려 해서 난 오류 (성공으로 본다) */
    open val notModified: Boolean get() = false
}
