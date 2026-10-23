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

    /** 음성 메시지로 답할 수 있는지 (V-03) */
    val supportsVoice: Boolean get() = false

    /** OGG/Opus 음성 메시지를 보낸다 */
    suspend fun sendVoice(chat: String, ogg: ByteArray) {}

    /** 만든 이미지(PNG)를 사진으로 보낸다. 보낼 수 없으면 false */
    suspend fun sendImage(chat: String, png: ByteArray, caption: String?): Boolean = false
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
        /** 공개 대화방에서 봇을 불렀다 (@멘션). 공개 대화방은 부른 글만 받는다 */
        val mentioned: Boolean = false,
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

    /**
     * 메시지에 단 반응 (👍·👎 → 피드백, L-20). [emoji]는 이모지 글자 또는 플랫폼 이름(`+1`·`thumbsup`). [added]가 false면 뗐다
     */
    data class Reaction(
        override val userId: String,
        override val userName: String?,
        val chat: String,
        val messageId: String,
        val emoji: String,
        val added: Boolean,
        val direct: Boolean = true,
    ) : Inbound

    /** 플랫폼 입력 창(Discord 모달·Slack 모달)에서 수정한 승인 값 */
    data class EditSubmitted(
        override val userId: String,
        override val userName: String?,
        val requestId: String,
        val text: String,
    ) : Inbound
}

class InboundFile(
    val name: String,
    val size: Long?,
    /** 플랫폼이 알려 준 종류. 문서로 와도 이름이 이미지면 이미지로 본다 */
    val kind: FileKind = FileKind.DOCUMENT,
    val download: suspend (maxBytes: Long) -> ByteArray,
)

enum class FileKind { DOCUMENT, VOICE, IMAGE }

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

/** 알림 카드의 버튼 처리 (M16). 버튼 값이 `[prefix]:키:동작`이면 여기로 온다 */
interface ChannelActions {
    val prefix: String
    /** 이 버튼 머리([prefix])를 처리하는가 (여러 처리기를 묶을 때 [ChannelActionSet]) */
    fun handles(head: String): Boolean = head == prefix
    /** [user]가 누른 버튼. 돌려준 글을 카드에 덧붙인다 */
    suspend fun onButton(user: dev.vesta.core.UserContext, data: String): String
}

/** 사용자에게 먼저 보내는 알림 (주간 점검 결과·승인 요청, M16) */
interface ChannelNotifier {
    val name: String
    /** 어느 메신저인지 (웹 UI 연결 화면에 켜진 메신저를 보여 줄 때, M20) */
    val channel: dev.vesta.core.ChannelType
    /**
     * 보낸 대화방 수. [exact]면 글을 자르지 않는다: 이 메신저 한도를 넘으면 보내지 않고 0
     * (승인 카드는 잘린 명령에 버튼이 붙으면 안 된다, M20)
     */
    suspend fun notify(user: dev.vesta.core.UserId, key: String, text: String, buttons: List<ChannelButton>, exact: Boolean = false): Int
}

/** 여러 버튼 처리기를 하나로 (주간 점검 "ev", 셸 승인 넘기기 "sh" 등, M20) */
class ChannelActionSet(private val actions: List<ChannelActions>) : ChannelActions {
    override val prefix: String = actions.joinToString("|") { it.prefix }
    override fun handles(head: String) = actions.any { it.handles(head) }
    override suspend fun onButton(user: dev.vesta.core.UserContext, data: String): String =
        actions.first { it.handles(data.substringBefore(':')) }.onButton(user, data)
}
