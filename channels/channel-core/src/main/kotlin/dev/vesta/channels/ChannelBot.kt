package dev.vesta.channels

import dev.vesta.core.AgentType
import dev.vesta.core.RiskLevel
import dev.vesta.core.UserId
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.channel.ChannelBinding
import dev.vesta.core.channel.ChannelBindingStore
import dev.vesta.core.config.MediaConfig
import dev.vesta.core.config.MessengerConfig
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.media.AudioFormat
import dev.vesta.core.media.ImageInput
import dev.vesta.core.media.MediaException
import dev.vesta.core.media.SpeechToText
import dev.vesta.core.media.TextToSpeech
import dev.vesta.core.tools.ApprovalDecision
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * 메신저 공통 동작 (PROJECT.md 6.5·6.6): 계정 연결, 명령어, 답변 스트리밍, 승인 버튼, 파일 첨부.
 * - 연결된 계정(1:1 대화)의 메시지만 처리하고, 나머지는 답하지 않고 로그만 남긴다.
 * - 대화방 하나에 질문 하나씩. 답변은 메시지를 고쳐 가며 보여 주고, 승인은 버튼으로 받는다.
 *
 * 상태(대화방별 작업·승인)는 한 스레드에서만 고친다: 어댑터는 [handle]과 [handle]이 띄우는 작업을
 * 단일 스레드 디스패처에서 돌린다.
 */
class ChannelBot(
    private val surface: ChatSurface,
    private val conversation: ConversationService,
    private val bindings: ChannelBindingStore,
    private val files: ChannelFiles,
    private val config: MessengerConfig,
    private val status: suspend () -> String = { "정상" },
    private val clock: Clock = Clock.systemUTC(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** 음성 메시지 인식 (V-01). 없으면 음성은 받지 않는다 */
    private val speechToText: SpeechToText? = null,
    /** 음성 답변 (V-03). 음성으로 물었고 `/voice`를 켰을 때만 */
    private val textToSpeech: TextToSpeech? = null,
    private val mediaLimits: MediaConfig = MediaConfig(),
    /** 알림 카드의 버튼 처리 (주간 점검 제안 승인 등, M16) */
    private val actions: ChannelActions? = null,
) : ChannelNotifier {
    private val channel = surface.channel
    override val name: String get() = surface.name
    /** 보낸 알림 카드: "대화방|키" → (메시지 ID, 본문). 버튼을 누르면 결과를 덧붙여 고친다. 알림은 다른 작업에서 보내므로 동시 접근용 */
    private val notices = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private val chats = mutableMapOf<String, ChatState>()
    private var pairingFailures = 0

    private class ApprovalMessage(val card: ApprovalCard, val field: String?, val value: String?) {
        /** 카드를 보내기 전에 등록하므로(보내는 사이 누른 버튼도 받게) 처음에는 null */
        var messageId: String? = null
        var resolved = false
        /** 카드를 보내기 전에 끝났으면 보낸 뒤에 적을 결과 */
        var result: Pair<String, String?>? = null
    }

    private class Attachment(val name: String, val path: Path)

    private class FileOffer(val messageId: String, val attachment: Attachment, val collections: List<String>)

    private class ChatState {
        var job: Job? = null
        /** "수정" 버튼을 누른 승인 요청. 다음 글이 수정한 값 */
        var pendingEdit: String? = null
        /** "이 파일에 대해 질문"을 고른 파일. 다음 질문에 붙인다 */
        var attachment: Attachment? = null
        val approvals = mutableMapOf<String, ApprovalMessage>()
        val offers = LinkedHashMap<String, FileOffer>()
        /** 음성으로 물으면 음성으로도 답할지 (`/voice`로 바꾼다. 서버를 다시 시작하면 설정값으로) */
        var voiceReply: Boolean? = null
    }

    /** 받은 일 하나 처리. 답변 생성처럼 오래 걸리는 일은 [scope]에 띄운다 */
    suspend fun handle(event: Inbound, scope: CoroutineScope) {
        when (event) {
            is Inbound.Message -> onMessage(event, scope)
            is Inbound.Button -> onButton(event, scope)
            is Inbound.EditSubmitted -> onEditSubmitted(event)
        }
    }

    private fun who(event: Inbound) = "id=${event.userId} @${event.userName}"

    private suspend fun onMessage(message: Inbound.Message, scope: CoroutineScope) {
        if (!message.direct) {
            log.info { "1:1 대화가 아닌 ${surface.name} 메시지 무시 (${message.chat})" }
            return
        }
        // 서버가 꺼져 있던 동안 쌓인 오래된 메시지는 실행하지 않는다
        val sentAt = message.sentAt
        if (sentAt != null && clock.instant().epochSecond - sentAt.epochSecond > STALE_SECONDS) {
            log.info { "오래된 ${surface.name} 메시지 무시 ($sentAt)" }
            return
        }
        val chat = message.chat
        val text = message.text?.trim()
        val binding = bindings.find(channel, message.userId)
        if (binding == null) {
            val code = text?.let(::pairingCode)
            if (code != null) pair(message, code)
            else log.warn { "연결되지 않은 ${surface.name} 계정의 메시지 무시: ${who(message)}" }
            return
        }
        val state = chats.getOrPut(chat) { ChatState() }
        if (message.file != null) return onFile(binding, state, chat, message.file, text, scope)
        if (text.isNullOrEmpty()) return send(chat, "글·음성·사진이나 문서 파일(.txt .md .pdf)을 보내 주세요.")

        when (command(text)) {
            "start", "help" -> return send(chat, help())
            "new" -> {
                state.job?.cancel()
                state.attachment = null
                bindings.setSession(channel, binding.externalUserId, null)
                return send(chat, "새 대화를 시작합니다.")
            }
            "cancel" -> {
                val job = state.job
                if (job?.isActive == true) job.cancel() else send(chat, "진행 중인 답변이 없습니다.")
                return
            }
            "status" -> return send(chat, runCatching { status() }.getOrElse { "상태 확인 실패: ${it.message}" })
            "voice" -> {
                if (!surface.supportsVoice || textToSpeech == null) return send(chat, "이 메신저에서는 음성 답변을 쓸 수 없습니다.")
                val on = !(state.voiceReply ?: config.voiceReply)
                state.voiceReply = on
                return send(chat, if (on) "음성으로 물으면 음성으로도 답합니다." else "음성 답변을 껐습니다.")
            }
        }
        state.pendingEdit?.let { id ->
            state.pendingEdit = null
            return resolveEdited(binding, state, chat, id, text)
        }
        if (state.job?.isActive == true) return send(chat, "앞 질문에 답하는 중입니다. 멈추려면 ${surface.commandPrefix}cancel")
        val attachment = state.attachment
        state.attachment = null
        val question = routed(text)
        val input = if (attachment != null) withFile(chat, attachment, question) ?: return else question
        startTurn(binding, state, chat, UserInput(input), scope)
    }

    /** `/new`, `!new`, `/new@봇이름` → "new". 엔진 명령이 아니면 null */
    private fun command(text: String): String? {
        if (text.firstOrNull() !in PREFIXES) return null
        val word = text.drop(1).substringBefore(' ').substringBefore('\n').substringBefore('@').lowercase()
        return word.takeIf { it in ENGINE_COMMANDS }
    }

    /** `!web 질문`처럼 `!`로 쓴 라우팅 명령을 대화 엔진이 아는 `/web`으로 바꾼다 */
    private fun routed(text: String): String {
        if (!text.startsWith('!')) return text
        val word = text.drop(1).substringBefore(' ').substringBefore('\n').lowercase()
        return if (word in ROUTE_COMMANDS) "/" + text.drop(1) else text
    }

    /** `/start 코드`, `!start 코드`, 또는 코드만 */
    private fun pairingCode(text: String): String? {
        val code = START.find(text)?.groupValues?.get(1) ?: text
        return code.trim().takeIf { CODE.matches(it) }
    }

    /** 이 Mac에서 만든 일회용 코드로 계정을 연결한다. 5번 틀리면 코드를 지운다 */
    private suspend fun pair(message: Inbound.Message, code: String) {
        val chat = message.chat
        val user = bindings.consumePairing(channel, PairingCode.hash(code))
        if (user == null) {
            pairingFailures++
            log.warn { "${surface.name} 연결 코드 불일치 ($pairingFailures/$MAX_PAIRING_FAILURES): ${who(message)}" }
            if (pairingFailures >= MAX_PAIRING_FAILURES) {
                bindings.clearPairings(channel)
                pairingFailures = 0
                log.warn { "연결 코드를 여러 번 틀려 코드를 지웠습니다. vesta ${surface.key} pair 로 새로 만드세요" }
            }
            return send(chat, "연결 코드가 맞지 않거나 만료됐습니다.")
        }
        pairingFailures = 0
        bindings.bind(user.userId, channel, message.userId, chat)
        log.info { "${surface.name} 계정 연결: ${who(message)}" }
        send(chat, "연결됐습니다.\n\n${help()}")
    }

    /** [voice]면 음성으로 들어온 질문 (음성 답변 대상) */
    private fun startTurn(binding: ChannelBinding, state: ChatState, chat: String, input: UserInput, scope: CoroutineScope, voice: Boolean = false) {
        state.job = scope.launch { runTurn(binding, state, chat, input, voice) }
    }

    private suspend fun runTurn(binding: ChannelBinding, state: ChatState, chat: String, input: UserInput, voice: Boolean) {
        // 생성 중 메시지 수정은 이 턴의 하위 작업으로 (턴이 끝나거나 취소되면 함께 멈춘다)
        val turnScope = CoroutineScope(currentCoroutineContext())
        fun newReply() = LiveReply(surface, chat, config.streamIntervalMillis, nowMillis, turnScope).also {
            // 로컬 자료를 보내지 않는 설정이면 어느 에이전트가 맡을지 알기 전까지 글자를 보여 주지 않는다
            it.streaming = config.allowLocalSources
        }
        var reply = newReply()
        // 승인 요청 앞에서 끝낸 답변 조각들. 승인 뒤 답변은 승인 카드 아래 새 메시지로 이어 간다
        val earlier = StringBuilder()
        var evidence = emptyList<Evidence>()
        var done: AgentEvent.Done? = null
        var error: String? = null
        var unsupported = 0
        var withheld = false
        var cancelled = false
        val opened = mutableListOf<String>()
        // 만든 이미지는 답변을 마친 뒤 그 아래에 보낸다
        val images = mutableListOf<AgentEvent.Media>()
        // 단계별 시간 (느린 답변 진단): 첫 이벤트·라우팅·첫 글자·생성 끝·마무리
        val began = nowMillis()
        val marks = linkedMapOf<String, Long>()
        fun mark(name: String) { marks.putIfAbsent(name, nowMillis() - began) }
        var apiMillis = 0L
        var apiCalls = 0
        try {
            reply.start("생각 중…")
            mark("시작 메시지")
            conversation.send(binding.user, channel, binding.sessionId, input).collect { event ->
                mark("첫 이벤트")
                if (event is AgentEvent.Routed) mark("라우팅")
                if (event is AgentEvent.Token) mark("첫 글자")
                if (event is AgentEvent.Done) mark("생성 끝")
                when (event) {
                    is AgentEvent.SessionReady -> if (event.sessionId != binding.sessionId) {
                        bindings.setSession(channel, binding.externalUserId, event.sessionId)
                    }
                    is AgentEvent.Routed -> {
                        if (!config.allowLocalSources && event.decision.agent in LOCAL_AGENTS) withheld = true
                        reply.status(ChannelText.agentName(event.decision.agent))
                    }
                    is AgentEvent.Status -> reply.status(event.text)
                    is AgentEvent.Reasoning -> Unit
                    is AgentEvent.Media -> images += event
                    is AgentEvent.Saved -> Unit
                    is AgentEvent.ToolStarted -> reply.status("${event.tool} · ${event.summary}")
                    // 시간 초과 등으로 끝난 승인 요청의 버튼을 닫는다
                    is AgentEvent.ToolFinished -> opened.forEach { expire(state, chat, it) }
                    is AgentEvent.ApprovalRequested -> {
                        val segment = reply.answer
                        reply.finish(if (withheld || !reply.streaming || segment.isBlank()) "아래 요청을 확인해 주세요." else segment, null)
                        earlier.append(segment)
                        apiMillis += reply.apiMillis; apiCalls += reply.apiCalls
                        reply = newReply()
                        opened += event.requestId
                        requestApproval(binding, state, chat, event)
                    }
                    is AgentEvent.Sources -> {
                        evidence = event.evidence
                        if (!config.allowLocalSources && event.evidence.any(ChannelText::isLocal)) withheld = true
                    }
                    is AgentEvent.Grounding -> unsupported = event.unsupported.size
                    is AgentEvent.Token -> reply.append(event.text)
                    is AgentEvent.Done -> done = event
                    is AgentEvent.Error -> error = event.message
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Exception) {
            log.warn(e) { "${surface.name} 답변 실패" }
            error = e.message ?: e::class.simpleName
        } finally {
            withContext(NonCancellable) {
                opened.forEach { expire(state, chat, it) }
                val answer = earlier.toString() + reply.answer
                val body = when {
                    withheld -> "로컬 자료가 들어간 답변이라 이 메신저로 보내지 않았습니다 (channels.${surface.key}.allowLocalSources: false). 웹 UI나 CLI에서 확인하세요."
                    else -> buildString {
                        append(reply.answer.trim())
                        if (cancelled) append("\n\n(중단됨)")
                        error?.let { append("\n\n오류: ").append(it) }
                    }.trim().ifEmpty { if (earlier.isNotBlank()) "" else if (cancelled) "(중단됨)" else "(답변 없음)" }
                }
                mark("대화 끝")
                runCatching { reply.finish(body, if (withheld) null else footer(answer, evidence, done, unsupported)) }
                    .onFailure { log.warn { "${surface.name} 답변 마무리 실패: ${it.message}" } }
                mark("마무리")
                log.info {
                    "${surface.name} 답변 ${done?.agent ?: "-"} ${nowMillis() - began}ms (" + marks.entries.joinToString { "${it.key} ${it.value}" } +
                        ") · 메신저 API ${apiCalls + reply.apiCalls}회 ${apiMillis + reply.apiMillis}ms"
                }
                images.forEach { image ->
                    val sent = runCatching { surface.sendImage(chat, image.bytes, image.caption) }
                        .onFailure { log.warn { "${surface.name} 이미지 보내기 실패: ${it.message}" } }
                        .getOrDefault(false)
                    if (!sent) runCatching { send(chat, "이미지를 이 메신저로 보내지 못했습니다. 웹 UI 갤러리에서 볼 수 있습니다.") }
                }
            }
        }
        // 음성으로 물었으면 답도 읽어 준다 (코드·표·출처는 읽지 않음, V-03)
        val tts = textToSpeech
        if (voice && !cancelled && !withheld && tts != null && surface.supportsVoice && (state.voiceReply ?: config.voiceReply)) {
            val answer = earlier.toString() + reply.answer
            if (answer.isNotBlank()) {
                try {
                    surface.sendVoice(chat, tts.synthesize(answer, AudioFormat.OGG_OPUS))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn { "${surface.name} 음성 답변 실패: ${e.message}" }
                }
            }
        }
    }

    private fun footer(answer: String, evidence: List<Evidence>, done: AgentEvent.Done?, unsupported: Int): ReplyFooter {
        val sources = ChannelText.citedSources(answer, evidence).map { e ->
            if (ChannelText.isLocal(e)) FooterSource(e.citation!!, ChannelText.localLabel(e), null)
            else FooterSource(e.citation!!, e.title.ifBlank { e.ref }.take(80), e.ref)
        }
        return ReplyFooter(sources, unsupported, done?.let { ChannelText.agentName(it.agent) })
    }

    /**
     * 승인 요청 (PROJECT.md 6.6): 명령 전체·이유를 그대로 보여 주고 승인/수정/거부 버튼을 단다.
     * 버튼은 연결된 본인만 누를 수 있고(브로커가 요청자와 같은지 확인), 한 번 쓰면 닫힌다.
     */
    private suspend fun requestApproval(binding: ChannelBinding, state: ChatState, chat: String, event: AgentEvent.ApprovalRequested) {
        val id = event.requestId
        val limit = surface.approvalBodyLimit
        val deny = when {
            event.risk == RiskLevel.EXEC && !config.allowExecApproval -> "이 메신저에서는 셸 승인을 받지 않도록 설정돼 있어 거부했습니다 (channels.${surface.key}.allowExecApproval: false)."
            // 실행할 내용을 전부 보여 줄 수 없으면 승인받지 않는다
            event.risk == RiskLevel.EXEC && event.details.body.length > limit -> "명령이 너무 길어 이 메신저에 전부 보여 줄 수 없어 거부했습니다. CLI나 웹 UI에서 요청하세요."
            else -> null
        }
        if (deny != null) {
            conversation.resolveApproval(binding.user, id, ApprovalDecision.Deny(deny))
            return send(chat, deny)
        }
        val body = event.details.body.let { if (it.length > limit) it.take(limit) + "\n… (앞부분만 표시)" else it }
        val card = ApprovalCard(
            tool = event.tool,
            risk = ChannelText.riskName(event.risk),
            title = event.details.title,
            body = body,
            // 셸 승인 내용에 이유가 이미 들어 있으면 다시 쓰지 않는다
            reason = event.reason?.takeIf { it !in event.details.body },
            warning = event.details.warning,
        )
        val buttons = buildList {
            add(ChannelButton("승인", "ap:$id:a", ButtonStyle.PRIMARY))
            if (event.editableField != null) add(ChannelButton("수정", "ap:$id:e"))
            add(ChannelButton("거부", "ap:$id:d", ButtonStyle.DANGER))
        }
        val approval = ApprovalMessage(card, event.editableField, event.editableValue)
        state.approvals[id] = approval
        approval.messageId = surface.sendApproval(chat, card, buttons)
        approval.result?.let { (result, edited) -> close(chat, approval, result, edited) }
    }

    private suspend fun onButton(button: Inbound.Button, scope: CoroutineScope) {
        val chat = button.chat
        val binding = bindings.find(channel, button.userId)
        if (binding == null || chat == null || chat != binding.externalChatId) {
            log.warn { "연결되지 않은 ${surface.name} 계정의 버튼 무시: ${who(button)}" }
            return button.reply.ack("권한이 없습니다")
        }
        val state = chats.getOrPut(chat) { ChatState() }
        val parts = button.data.split(':')
        when (parts.getOrNull(0)) {
            actions?.prefix -> onActionButton(binding, chat, button.reply, button.data, parts)
            "ap" -> onApprovalButton(binding, state, chat, button.reply, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
            "fq", "fc" -> onFileButton(binding, state, chat, button.reply, parts, scope)
            else -> button.reply.ack()
        }
    }

    /**
     * 사용자에게 먼저 보내는 알림 (M16). [user]와 연결된 대화방마다 보낸다. [key]가 같은 버튼(`키:동작`)을 누르면
     * [actions]가 처리하고 결과를 카드에 덧붙인다. 보낸 대화방 수를 돌려준다
     */
    override suspend fun notify(user: UserId, key: String, text: String, buttons: List<ChannelButton>): Int {
        val chats = bindings.list(channel).filter { it.user.userId == user }.map { it.externalChatId }.distinct()
        val body = if (text.length > surface.approvalBodyLimit) text.take(surface.approvalBodyLimit) + "\n…" else text
        return chats.count { chat ->
            runCatching {
                val id = if (buttons.isEmpty()) surface.sendText(chat, body) else surface.sendButtons(chat, body, listOf(buttons))
                if (buttons.isNotEmpty()) notices["$chat|$key"] = id to body
            }.onFailure { log.warn { "${surface.name} 알림 실패: ${it.message}" } }.isSuccess
        }
    }

    private suspend fun onActionButton(binding: ChannelBinding, chat: String, reply: ButtonReply, data: String, parts: List<String>) {
        val handler = actions ?: return reply.ack()
        val key = parts.take(2).joinToString(":")
        val result = try {
            handler.onButton(binding.user, data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "${surface.name} 버튼 처리 실패: $data" }
            "처리하지 못했습니다: ${e.message}"
        }
        reply.ack(result.take(150), minor = true)
        notices.remove("$chat|$key")?.let { (messageId, body) -> runCatching { surface.editText(chat, messageId, "$body\n\n→ $result") } }
    }

    private suspend fun onApprovalButton(binding: ChannelBinding, state: ChatState, chat: String, reply: ButtonReply, id: String, action: String) {
        val approval = state.approvals[id]
        if (approval == null || approval.resolved) return reply.ack("이미 끝난 요청입니다")
        if (action == "e" && approval.field != null) {
            if (reply.openEditor(id, approval.value.orEmpty())) return
            state.pendingEdit = id
            reply.ack()
            surface.askEdit(chat, approval.value.orEmpty())
            return
        }
        val decision = if (action == "a") ApprovalDecision.Approve else ApprovalDecision.Deny("사용자가 거부함 (${surface.name})")
        val ok = conversation.resolveApproval(binding.user, id, decision)
        if (state.pendingEdit == id) state.pendingEdit = null
        mark(state, chat, id, if (!ok) "만료됨" else if (action == "a") "승인함" else "거부함")
        if (!ok) reply.ack("만료된 요청입니다") else reply.ack(if (action == "a") "승인했습니다" else "거부했습니다", minor = true)
    }

    /** 입력 창에서 고친 값. 대화방은 연결된 대화방으로 본다 */
    private suspend fun onEditSubmitted(event: Inbound.EditSubmitted) {
        val binding = bindings.find(channel, event.userId)
        if (binding == null) {
            log.warn { "연결되지 않은 ${surface.name} 계정의 수정 무시: ${who(event)}" }
            return
        }
        val chat = binding.externalChatId
        val state = chats.getOrPut(chat) { ChatState() }
        resolveEdited(binding, state, chat, event.requestId, event.text.trim())
    }

    private suspend fun resolveEdited(binding: ChannelBinding, state: ChatState, chat: String, id: String, text: String) {
        val approval = state.approvals[id]
        val field = approval?.field
        if (approval == null || approval.resolved || field == null) return send(chat, "이미 끝난 요청입니다.")
        if (text.isEmpty()) return send(chat, "빈 명령은 승인할 수 없습니다.")
        val ok = conversation.resolveApproval(binding.user, id, ApprovalDecision.ApproveEdited(JsonObject(mapOf(field to JsonPrimitive(text)))))
        if (ok) mark(state, chat, id, "수정해서 승인함", text) else mark(state, chat, id, "만료됨")
        if (!ok) send(chat, "만료된 요청입니다.")
    }

    /** 승인 메시지를 결과로 고치고 버튼을 없앤다 */
    private suspend fun mark(state: ChatState, chat: String, id: String, result: String, edited: String? = null) {
        val approval = state.approvals[id] ?: return
        if (approval.resolved) return
        approval.resolved = true
        approval.result = result to edited
        close(chat, approval, result, edited)
    }

    private suspend fun close(chat: String, approval: ApprovalMessage, result: String, edited: String?) {
        val messageId = approval.messageId ?: return
        runCatching { surface.closeApproval(chat, messageId, approval.card, result, edited) }
            .onFailure { log.warn { "승인 메시지 수정 실패: ${it.message}" } }
    }

    private suspend fun expire(state: ChatState, chat: String, id: String) {
        if (state.pendingEdit == id) state.pendingEdit = null
        mark(state, chat, id, "만료됨")
        state.approvals.remove(id)
    }

    /** 음성 (V-01·V-02): 인식한 글을 먼저 보여 주고 그 글로 묻는다. 승인은 음성이 아니라 버튼으로만 받는다 */
    private suspend fun onVoice(binding: ChannelBinding, state: ChatState, chat: String, file: InboundFile, scope: CoroutineScope) {
        val stt = speechToText ?: return send(chat, "음성 인식이 꺼져 있습니다. 글로 보내 주세요.")
        if (state.job?.isActive == true) return send(chat, "앞 질문에 답하는 중입니다. 멈추려면 ${surface.commandPrefix}cancel")
        val maxBytes = mediaLimits.maxAudioMegabytes * 1024L * 1024
        if ((file.size ?: 0) > maxBytes) return send(chat, "음성이 너무 깁니다 (최대 ${mediaLimits.maxAudioMegabytes}MB).")
        val text = try {
            stt.transcribe(file.download(maxBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaException) {
            return send(chat, e.message ?: "음성을 알아듣지 못했습니다.")
        } catch (e: Exception) {
            log.warn { "${surface.name} 음성 인식 실패: ${e.message}" }
            return send(chat, "음성을 받지 못했습니다: ${e.message}")
        }
        if (text.isBlank()) return send(chat, "음성을 알아듣지 못했습니다. 다시 말하거나 글로 보내 주세요.")
        send(chat, "🎤 $text")
        startTurn(binding, state, chat, UserInput(text), scope, voice = true)
    }

    /** 사진·스크린샷 (V-04): 설명(캡션)을 질문으로, 없으면 이미지 설명 */
    private suspend fun onImage(binding: ChannelBinding, state: ChatState, chat: String, file: InboundFile, caption: String?, scope: CoroutineScope) {
        if (state.job?.isActive == true) return send(chat, "앞 질문에 답하는 중입니다. 멈추려면 ${surface.commandPrefix}cancel")
        val maxBytes = mediaLimits.maxImageMegabytes * 1024L * 1024
        if ((file.size ?: 0) > maxBytes) return send(chat, "이미지가 너무 큽니다 (최대 ${mediaLimits.maxImageMegabytes}MB).")
        val image = try {
            ImageInput.of(file.download(maxBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "${surface.name} 이미지 받기 실패: ${e.message}" }
            return send(chat, "이미지를 받지 못했습니다: ${e.message}")
        } ?: return send(chat, "PNG·JPEG·WebP·GIF 이미지만 읽을 수 있습니다.")
        startTurn(binding, state, chat, UserInput(caption?.let(::routed).orEmpty(), listOf(image)), scope)
    }

    /** 문서 파일 (PROJECT.md 6.5 파일 첨부): 설명(캡션)이 있으면 바로 질문, 없으면 질문/컬렉션 추가를 고르게 한다 */
    private suspend fun onFile(binding: ChannelBinding, state: ChatState, chat: String, file: InboundFile, caption: String?, scope: CoroutineScope) {
        val name = file.name
        val ext = name.substringAfterLast('.', "").lowercase()
        if (file.kind == FileKind.VOICE || (file.kind == FileKind.DOCUMENT && ext in AUDIO_EXTENSIONS)) return onVoice(binding, state, chat, file, scope)
        if (file.kind == FileKind.IMAGE || ext in IMAGE_EXTENSIONS) return onImage(binding, state, chat, file, caption, scope)
        if (!files.supports(name)) return send(chat, "읽을 수 있는 파일은 .txt .md .pdf 입니다.")
        val maxBytes = config.maxFileMegabytes * 1024L * 1024
        if ((file.size ?: 0) > maxBytes) return send(chat, "파일이 너무 큽니다 (최대 ${config.maxFileMegabytes}MB).")
        val path = try {
            files.save(binding.user, channel, name, file.download(maxBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "${surface.name} 파일 받기 실패: ${e.message}" }
            return send(chat, "파일을 받지 못했습니다: ${e.message}")
        }
        val attachment = Attachment(name, path)
        if (!caption.isNullOrEmpty()) {
            if (state.job?.isActive == true) return send(chat, "앞 질문에 답하는 중입니다. 멈추려면 ${surface.commandPrefix}cancel")
            val input = withFile(chat, attachment, caption) ?: return
            return startTurn(binding, state, chat, UserInput(input), scope)
        }
        val collections = files.collections(binding.user).take(MAX_COLLECTION_BUTTONS)
        val key = UUID.randomUUID().toString().take(8)
        val rows = listOf(listOf(ChannelButton("이 파일에 대해 질문", "fq:$key", ButtonStyle.PRIMARY))) +
            collections.mapIndexed { i, c -> listOf(ChannelButton("'${c.take(40)}' 컬렉션에 추가", "fc:$key:$i")) }
        val sent = surface.sendButtons(chat, "받았습니다: $name\n무엇을 할까요?", rows)
        state.offers[key] = FileOffer(sent, attachment, collections)
        while (state.offers.size > MAX_OFFERS) state.offers.remove(state.offers.keys.first())
    }

    private suspend fun onFileButton(binding: ChannelBinding, state: ChatState, chat: String, reply: ButtonReply, parts: List<String>, scope: CoroutineScope) {
        val offer = state.offers.remove(parts.getOrNull(1).orEmpty()) ?: return reply.ack("이미 끝난 요청입니다")
        val name = offer.attachment.name
        reply.ack()
        if (parts[0] == "fq") {
            state.attachment = offer.attachment
            surface.editText(chat, offer.messageId, "'$name'에 대해 물어볼 내용을 보내 주세요.")
            return
        }
        val collection = parts.getOrNull(2)?.toIntOrNull()?.let { offer.collections.getOrNull(it) } ?: return
        surface.editText(chat, offer.messageId, "'$name'을(를) '$collection' 컬렉션에 색인하는 중…")
        scope.launch {
            val result = try {
                files.addToCollection(binding.user, collection, offer.attachment.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "${surface.name} 파일 색인 실패" }
                "색인 실패: ${e.message}"
            }
            runCatching { surface.editText(chat, offer.messageId, "'$name' → '$collection': $result") }
        }
    }

    /** 파일 본문을 붙인 질문. 일반 대화로 보낸다 (파일 내용이 웹 검색어로 나가지 않게) */
    private suspend fun withFile(chat: String, attachment: Attachment, question: String): String? {
        val content = try {
            files.readText(attachment.path, FILE_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(chat, "파일을 읽지 못했습니다: ${e.message}")
            return null
        }
        return "/chat 첨부 파일 \"${attachment.name}\"의 내용입니다.\n\n<<<\n$content\n>>>\n\n$question"
    }

    private suspend fun send(chat: String, text: String) {
        surface.sendText(chat, text)
    }

    private fun help() = help(surface.commandPrefix)

    companion object {
        /** 이보다 오래된 메시지는 실행하지 않는다 */
        const val STALE_SECONDS = 600L
        const val MAX_PAIRING_FAILURES = 5
        const val FILE_CHARS = 30_000
        private const val MAX_COLLECTION_BUTTONS = 6
        private const val MAX_OFFERS = 10

        private val PREFIXES = setOf('/', '!')
        private val ENGINE_COMMANDS = setOf("start", "help", "new", "cancel", "status", "voice")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
        private val AUDIO_EXTENSIONS = setOf("ogg", "oga", "opus", "m4a", "mp3", "wav", "webm")
        private val ROUTE_COMMANDS = setOf("web", "docs", "chat", "image")
        private val START = Regex("^[/!]start\\s+(.+)$", RegexOption.IGNORE_CASE)
        /** [PairingCode] 8자 (가운데 하이픈·공백 허용) */
        private val CODE = Regex("^[A-Za-z0-9]{4}[- ]?[A-Za-z0-9]{4}$")

        /** 로컬 데이터를 답변에 쓰는 에이전트 */
        private val LOCAL_AGENTS = setOf(AgentType.DOCS, AgentType.CODE, AgentType.TASK, AgentType.PERSONAL)

        /** 메신저 명령 목록 (Telegram 명령 메뉴) */
        val COMMANDS = listOf(
            "new" to "새 대화",
            "web" to "웹 검색해서 답하기",
            "docs" to "등록한 문서에서 찾아 답하기",
            "chat" to "일반 대화",
            "image" to "이미지 만들기",
            "cancel" to "답변 멈추기",
            "status" to "Vesta 상태",
            "voice" to "음성으로 물으면 음성으로도 답하기 (켜기/끄기)",
            "help" to "사용법",
        )

        fun help(prefix: Char) = """
            Vesta 사용법
            • 그냥 물어보면 알맞은 도우미가 답합니다.
            • ${prefix}web 질문 — 웹 검색
            • ${prefix}docs 질문, @컬렉션 질문 — 문서 검색
            • ${prefix}chat 질문 — 일반 대화
            • ${prefix}image 그릴 것 — 이미지 만들기 ("더 밝게"처럼 이어서 고칠 수 있음)
            • 문서 파일(.txt .md .pdf)을 보내면 그 파일에 대해 묻거나 컬렉션에 추가할 수 있습니다.
            • 음성 메시지로 물어도 됩니다. 사진·스크린샷에 설명을 붙여 보내면 그 이미지에 대해 답합니다.
            • ${prefix}new 새 대화 · ${prefix}cancel 답변 멈춤 · ${prefix}status 상태
        """.trimIndent()
    }
}
