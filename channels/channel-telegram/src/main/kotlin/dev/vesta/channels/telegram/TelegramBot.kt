package dev.vesta.channels.telegram

import dev.vesta.channels.ChannelFiles
import dev.vesta.channels.ChannelText
import dev.vesta.channels.PairingCode
import dev.vesta.channels.telegram.TelegramHtml.escape
import dev.vesta.core.AgentType
import dev.vesta.core.ChannelType
import dev.vesta.core.RiskLevel
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.channel.ChannelBinding
import dev.vesta.core.channel.ChannelBindingStore
import dev.vesta.core.config.TelegramConfig
import dev.vesta.core.conversation.ConversationService
import dev.vesta.core.knowledge.Evidence
import dev.vesta.core.tools.ApprovalDecision
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * Telegram 채널 어댑터 (PROJECT.md 6.4~6.6, I-05, M10).
 * - long polling으로 받으므로 공개 주소가 필요 없다.
 * - 연결된 계정(1:1 대화)의 메시지만 처리하고, 나머지는 답하지 않고 로그만 남긴다.
 * - 대화방 하나에 질문 하나씩. 답변은 메시지를 고쳐 가며 보여 주고, 승인은 인라인 버튼으로 받는다.
 *
 * 상태(대화방별 작업·승인)는 한 스레드에서만 고친다: [run]은 단일 스레드 디스패처에서 돈다.
 */
class TelegramBot(
    private val api: TelegramApi,
    private val conversation: ConversationService,
    private val bindings: ChannelBindingStore,
    private val files: ChannelFiles,
    private val config: TelegramConfig,
    private val status: suspend () -> String = { "정상" },
    private val clock: Clock = Clock.systemUTC(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val chats = mutableMapOf<Long, ChatState>()
    private var pairingFailures = 0

    private class ApprovalMessage(val messageId: Long, val html: String, val field: String?, val value: String?) {
        var resolved = false
    }

    private class Attachment(val name: String, val path: Path)

    private class FileOffer(val messageId: Long, val attachment: Attachment, val collections: List<String>)

    private class ChatState {
        var job: Job? = null
        /** "수정" 버튼을 누른 승인 요청. 다음 글이 수정한 값 */
        var pendingEdit: String? = null
        /** "이 파일에 대해 질문"을 고른 파일. 다음 질문에 붙인다 */
        var attachment: Attachment? = null
        val approvals = mutableMapOf<String, ApprovalMessage>()
        val offers = LinkedHashMap<String, FileOffer>()
    }

    /** 끝날 때까지 long polling. 토큰이 거부되면 멈춘다 */
    suspend fun run() = withContext(Dispatchers.Default.limitedParallelism(1)) {
        coroutineScope {
            val me = connect() ?: return@coroutineScope
            log.info { "Telegram 봇 시작: @${me.username}" }
            runCatching { api.setCommands(COMMANDS) }.onFailure { log.warn { "명령 목록 등록 실패: ${it.message}" } }
            var offset: Long? = null
            var backoff = 1_000L
            while (isActive) {
                val updates = try {
                    api.getUpdates(offset, POLL_SECONDS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TelegramException) {
                    if (e.code == 401) { log.error { "Telegram 토큰이 거부됐습니다. vesta telegram token 으로 다시 저장하세요" }; return@coroutineScope }
                    // 409: 다른 곳에서 같은 봇으로 getUpdates 중이거나 webhook이 걸려 있음
                    log.warn { "Telegram 받기 실패: ${e.message}" }
                    delay(e.retryAfterSeconds?.times(1000L) ?: if (e.code == 409) 30_000 else backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                    continue
                } catch (e: Exception) {
                    log.warn { "Telegram 연결 실패: ${e.message}" }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                    continue
                }
                backoff = 1_000L
                for (update in updates) {
                    offset = update.updateId + 1
                    try {
                        handle(update, this)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(e) { "Telegram 메시지 처리 실패 (update ${update.updateId})" }
                    }
                }
            }
        }
    }

    private suspend fun connect(): TgUser? {
        var wait = 2_000L
        while (true) {
            try {
                return api.getMe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramException) {
                if (e.code == 401) { log.error { "Telegram 토큰이 거부됐습니다. vesta telegram token 으로 다시 저장하세요" }; return null }
                log.warn { "Telegram 연결 실패: ${e.message}" }
            } catch (e: Exception) {
                log.warn { "Telegram 연결 실패: ${e.message}" }
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000)
        }
    }

    /** 업데이트 하나 처리. 답변 생성처럼 오래 걸리는 일은 [scope]에 띄운다 */
    internal suspend fun handle(update: TgUpdate, scope: CoroutineScope) {
        update.message?.let { onMessage(it, scope) }
        update.callbackQuery?.let { onCallback(it, scope) }
    }

    private suspend fun onMessage(message: TgMessage, scope: CoroutineScope) {
        val from = message.from ?: return
        if (message.chat.type != "private") {
            log.info { "1:1 대화가 아닌 Telegram 메시지 무시 (chat ${message.chat.id})" }
            return
        }
        // 서버가 꺼져 있던 동안 쌓인 오래된 메시지는 실행하지 않는다
        if (message.date > 0 && clock.instant().epochSecond - message.date > STALE_SECONDS) {
            log.info { "오래된 Telegram 메시지 무시 (${message.date})" }
            return
        }
        val chatId = message.chat.id
        val text = message.text?.trim()
        val binding = bindings.find(ChannelType.TELEGRAM, from.id.toString())
        if (binding == null) {
            if (text != null && text.startsWith("/start ")) pair(chatId, from, text.removePrefix("/start ").trim())
            else log.warn { "연결되지 않은 Telegram 계정의 메시지 무시: id=${from.id} @${from.username}" }
            return
        }
        val state = chats.getOrPut(chatId) { ChatState() }
        if (message.document != null) return onDocument(binding, state, chatId, message, scope)
        if (text.isNullOrEmpty()) return send(chatId, "글이나 문서 파일(.txt .md .pdf)을 보내 주세요.")

        when (text.substringBefore(' ').substringBefore('@').lowercase()) {
            "/start", "/help" -> return send(chatId, HELP)
            "/new" -> {
                state.job?.cancel()
                state.attachment = null
                bindings.setSession(ChannelType.TELEGRAM, binding.externalUserId, null)
                return send(chatId, "새 대화를 시작합니다.")
            }
            "/cancel" -> {
                val job = state.job
                if (job?.isActive == true) job.cancel() else send(chatId, "진행 중인 답변이 없습니다.")
                return
            }
            "/status" -> return send(chatId, runCatching { status() }.getOrElse { "상태 확인 실패: ${it.message}" })
        }
        state.pendingEdit?.let { id ->
            state.pendingEdit = null
            return resolveEdited(binding, state, chatId, id, text)
        }
        if (state.job?.isActive == true) return send(chatId, "앞 질문에 답하는 중입니다. 멈추려면 /cancel")
        val attachment = state.attachment
        state.attachment = null
        val input = if (attachment != null) withFile(chatId, attachment, text) ?: return else text
        startTurn(binding, state, chatId, input, scope)
    }

    /** `/start <코드>`: 이 Mac에서 만든 일회용 코드로 계정을 연결한다. 5번 틀리면 코드를 지운다 */
    private suspend fun pair(chatId: Long, from: TgUser, code: String) {
        val user = bindings.consumePairing(ChannelType.TELEGRAM, PairingCode.hash(code))
        if (user == null) {
            pairingFailures++
            log.warn { "Telegram 연결 코드 불일치 ($pairingFailures/$MAX_PAIRING_FAILURES): id=${from.id} @${from.username}" }
            if (pairingFailures >= MAX_PAIRING_FAILURES) {
                bindings.clearPairings(ChannelType.TELEGRAM)
                pairingFailures = 0
                log.warn { "연결 코드를 여러 번 틀려 코드를 지웠습니다. vesta telegram pair 로 새로 만드세요" }
            }
            return send(chatId, "연결 코드가 맞지 않거나 만료됐습니다.")
        }
        pairingFailures = 0
        bindings.bind(user.userId, ChannelType.TELEGRAM, from.id.toString(), chatId.toString())
        log.info { "Telegram 계정 연결: id=${from.id} @${from.username}" }
        send(chatId, "연결됐습니다.\n\n$HELP")
    }

    private fun startTurn(binding: ChannelBinding, state: ChatState, chatId: Long, input: String, scope: CoroutineScope) {
        state.job = scope.launch { runTurn(binding, state, chatId, input) }
    }

    private suspend fun runTurn(binding: ChannelBinding, state: ChatState, chatId: Long, input: String) {
        fun newReply() = LiveReply(api, chatId, config.streamIntervalMillis, nowMillis).also {
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
        try {
            reply.start("생각 중…")
            conversation.send(binding.user, ChannelType.TELEGRAM, binding.sessionId, UserInput(input)).collect { event ->
                when (event) {
                    is AgentEvent.SessionReady -> if (event.sessionId != binding.sessionId) {
                        bindings.setSession(ChannelType.TELEGRAM, binding.externalUserId, event.sessionId)
                    }
                    is AgentEvent.Routed -> {
                        if (!config.allowLocalSources && event.decision.agent in LOCAL_AGENTS) withheld = true
                        reply.status(ChannelText.agentName(event.decision.agent))
                    }
                    is AgentEvent.Status -> reply.status(event.text)
                    is AgentEvent.Reasoning -> Unit
                    is AgentEvent.ToolStarted -> reply.status("${event.tool} · ${event.summary}")
                    // 시간 초과 등으로 끝난 승인 요청의 버튼을 닫는다
                    is AgentEvent.ToolFinished -> opened.forEach { expire(state, chatId, it) }
                    is AgentEvent.ApprovalRequested -> {
                        val segment = reply.answer
                        reply.finish(if (withheld || !reply.streaming || segment.isBlank()) "아래 요청을 확인해 주세요." else segment, null)
                        earlier.append(segment)
                        reply = newReply()
                        opened += event.requestId
                        requestApproval(binding, state, chatId, event)
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
            log.warn(e) { "Telegram 답변 실패" }
            error = e.message ?: e::class.simpleName
        } finally {
            withContext(NonCancellable) {
                opened.forEach { expire(state, chatId, it) }
                val answer = earlier.toString() + reply.answer
                val body = when {
                    withheld -> "로컬 자료가 들어간 답변이라 텔레그램으로 보내지 않았습니다 (channels.telegram.allowLocalSources: false). 웹 UI나 CLI에서 확인하세요."
                    else -> buildString {
                        append(reply.answer.trim())
                        if (cancelled) append("\n\n(중단됨)")
                        error?.let { append("\n\n오류: ").append(it) }
                    }.trim().ifEmpty { if (earlier.isNotBlank()) "" else if (cancelled) "(중단됨)" else "(답변 없음)" }
                }
                runCatching { reply.finish(body, if (withheld) null else footer(answer, evidence, done, unsupported)) }
                    .onFailure { log.warn { "Telegram 답변 마무리 실패: ${it.message}" } }
            }
        }
    }

    private fun footer(answer: String, evidence: List<Evidence>, done: AgentEvent.Done?, unsupported: Int): String? {
        val lines = mutableListOf<String>()
        val cited = ChannelText.citedSources(answer, evidence)
        if (cited.isNotEmpty()) {
            lines += "<b>출처</b>"
            cited.forEach { e ->
                lines += "[${e.citation}] " + if (ChannelText.isLocal(e)) escape(ChannelText.localLabel(e)) else TelegramHtml.link(e.title.ifBlank { e.ref }.take(80), e.ref)
            }
        }
        if (unsupported > 0) lines += "<i>근거로 확인되지 않은 문장 ${unsupported}개</i>"
        done?.let { lines += "<i>${ChannelText.agentName(it.agent)}</i>" }
        return lines.joinToString("\n").ifEmpty { null }
    }

    /**
     * 승인 요청 (PROJECT.md 6.6): 명령 전체·이유를 그대로 보여 주고 승인/수정/거부 버튼을 단다.
     * 버튼은 연결된 본인만 누를 수 있고(브로커가 요청자와 같은지 확인), 한 번 쓰면 닫힌다.
     */
    private suspend fun requestApproval(binding: ChannelBinding, state: ChatState, chatId: Long, event: AgentEvent.ApprovalRequested) {
        val id = event.requestId
        val deny = when {
            event.risk == RiskLevel.EXEC && !config.allowExecApproval -> "텔레그램에서는 셸 승인을 받지 않도록 설정돼 있어 거부했습니다 (channels.telegram.allowExecApproval: false)."
            // 실행할 내용을 전부 보여 줄 수 없으면 승인받지 않는다
            event.risk == RiskLevel.EXEC && event.details.body.length > APPROVAL_BODY_LIMIT -> "명령이 너무 길어 텔레그램에 전부 보여 줄 수 없어 거부했습니다. CLI나 웹 UI에서 요청하세요."
            else -> null
        }
        if (deny != null) {
            conversation.resolveApproval(binding.user, id, ApprovalDecision.Deny(deny))
            return send(chatId, deny)
        }
        val body = event.details.body.let { if (it.length > APPROVAL_BODY_LIMIT) it.take(APPROVAL_BODY_LIMIT) + "\n… (앞부분만 표시)" else it }
        val html = buildString {
            append("<b>승인 필요</b> · ").append(escape(event.tool)).append(" (").append(ChannelText.riskName(event.risk)).append(")\n")
            append(escape(event.details.title.take(300))).append('\n')
            append("<pre>").append(escape(body)).append("</pre>")
            // 셸 승인 내용에 이유가 이미 들어 있으면 다시 쓰지 않는다
            event.reason?.takeIf { it !in event.details.body }?.let { append("\n이유: ").append(escape(it.take(300))) }
            event.details.warning?.let { append("\n<b>주의</b>: ").append(escape(it.take(300))) }
            append("\n<i>답하지 않으면 잠시 뒤 자동으로 거부됩니다.</i>")
        }
        val buttons = buildList {
            add(TgButton("승인", "ap:$id:a"))
            if (event.editableField != null) add(TgButton("수정", "ap:$id:e"))
            add(TgButton("거부", "ap:$id:d"))
        }
        val message = api.sendMessage(chatId, html, html = true, keyboard = TgKeyboard(listOf(buttons)))
        state.approvals[id] = ApprovalMessage(message.messageId, html, event.editableField, event.editableValue)
    }

    private suspend fun onCallback(query: TgCallbackQuery, scope: CoroutineScope) {
        val chatId = query.message?.chat?.id
        val binding = bindings.find(ChannelType.TELEGRAM, query.from.id.toString())
        if (binding == null || chatId == null || chatId.toString() != binding.externalChatId) {
            log.warn { "연결되지 않은 Telegram 계정의 버튼 무시: id=${query.from.id} @${query.from.username}" }
            return api.answerCallback(query.id, "권한이 없습니다")
        }
        val state = chats.getOrPut(chatId) { ChatState() }
        val parts = query.data.orEmpty().split(':')
        when (parts.getOrNull(0)) {
            "ap" -> onApprovalButton(binding, state, chatId, query, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
            "fq", "fc" -> onFileButton(binding, state, chatId, query, parts, scope)
            else -> api.answerCallback(query.id)
        }
    }

    private suspend fun onApprovalButton(binding: ChannelBinding, state: ChatState, chatId: Long, query: TgCallbackQuery, id: String, action: String) {
        val approval = state.approvals[id]
        if (approval == null || approval.resolved) return api.answerCallback(query.id, "이미 끝난 요청입니다")
        if (action == "e" && approval.field != null) {
            state.pendingEdit = id
            api.answerCallback(query.id)
            api.sendMessage(chatId, "수정한 명령을 보내 주세요. 지금 명령:\n<pre>${escape(approval.value.orEmpty())}</pre>", html = true, forceReply = true)
            return
        }
        val decision = if (action == "a") ApprovalDecision.Approve else ApprovalDecision.Deny("사용자가 거부함 (Telegram)")
        val ok = conversation.resolveApproval(binding.user, id, decision)
        if (state.pendingEdit == id) state.pendingEdit = null
        mark(state, chatId, id, if (!ok) "만료됨" else if (action == "a") "승인함" else "거부함")
        api.answerCallback(query.id, if (!ok) "만료된 요청입니다" else if (action == "a") "승인했습니다" else "거부했습니다")
    }

    private suspend fun resolveEdited(binding: ChannelBinding, state: ChatState, chatId: Long, id: String, text: String) {
        val approval = state.approvals[id]
        val field = approval?.field
        if (approval == null || approval.resolved || field == null) return send(chatId, "이미 끝난 요청입니다.")
        val ok = conversation.resolveApproval(binding.user, id, ApprovalDecision.ApproveEdited(JsonObject(mapOf(field to JsonPrimitive(text)))))
        mark(state, chatId, id, if (ok) "수정해서 승인함: <code>${escape(text)}</code>" else "만료됨")
        if (!ok) send(chatId, "만료된 요청입니다.")
    }

    /** 승인 메시지를 결과로 고치고 버튼을 없앤다 */
    private suspend fun mark(state: ChatState, chatId: Long, id: String, labelHtml: String) {
        val approval = state.approvals[id] ?: return
        if (approval.resolved) return
        approval.resolved = true
        runCatching { api.editMessage(chatId, approval.messageId, approval.html + "\n\n<b>→ $labelHtml</b>", html = true) }
            .onFailure { log.warn { "승인 메시지 수정 실패: ${it.message}" } }
    }

    private suspend fun expire(state: ChatState, chatId: Long, id: String) {
        if (state.pendingEdit == id) state.pendingEdit = null
        mark(state, chatId, id, "만료됨")
        state.approvals.remove(id)
    }

    /** 문서 파일 (PROJECT.md 6.5 파일 첨부): 설명(캡션)이 있으면 바로 질문, 없으면 질문/컬렉션 추가를 고르게 한다 */
    private suspend fun onDocument(binding: ChannelBinding, state: ChatState, chatId: Long, message: TgMessage, scope: CoroutineScope) {
        val document = message.document ?: return
        val name = document.fileName ?: "file"
        if (!files.supports(name)) return send(chatId, "읽을 수 있는 파일은 .txt .md .pdf 입니다.")
        val maxBytes = config.maxFileMegabytes * 1024L * 1024
        if ((document.fileSize ?: 0) > maxBytes) return send(chatId, "파일이 너무 큽니다 (최대 ${config.maxFileMegabytes}MB).")
        val path = try {
            files.save(binding.user, ChannelType.TELEGRAM, name, api.download(document.fileId, maxBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "Telegram 파일 받기 실패: ${e.message}" }
            return send(chatId, "파일을 받지 못했습니다: ${e.message}")
        }
        val attachment = Attachment(name, path)
        val caption = message.caption?.trim()
        if (!caption.isNullOrEmpty()) {
            if (state.job?.isActive == true) return send(chatId, "앞 질문에 답하는 중입니다. 멈추려면 /cancel")
            val input = withFile(chatId, attachment, caption) ?: return
            return startTurn(binding, state, chatId, input, scope)
        }
        val collections = files.collections(binding.user).take(MAX_COLLECTION_BUTTONS)
        val key = UUID.randomUUID().toString().take(8)
        val rows = listOf(listOf(TgButton("이 파일에 대해 질문", "fq:$key"))) +
            collections.mapIndexed { i, c -> listOf(TgButton("'$c' 컬렉션에 추가", "fc:$key:$i")) }
        val sent = api.sendMessage(chatId, "받았습니다: $name\n무엇을 할까요?", keyboard = TgKeyboard(rows))
        state.offers[key] = FileOffer(sent.messageId, attachment, collections)
        while (state.offers.size > MAX_OFFERS) state.offers.remove(state.offers.keys.first())
    }

    private suspend fun onFileButton(binding: ChannelBinding, state: ChatState, chatId: Long, query: TgCallbackQuery, parts: List<String>, scope: CoroutineScope) {
        val offer = state.offers.remove(parts.getOrNull(1).orEmpty()) ?: return api.answerCallback(query.id, "이미 끝난 요청입니다")
        val name = offer.attachment.name
        api.answerCallback(query.id)
        if (parts[0] == "fq") {
            state.attachment = offer.attachment
            api.editMessage(chatId, offer.messageId, "'$name'에 대해 물어볼 내용을 보내 주세요.")
            return
        }
        val collection = parts.getOrNull(2)?.toIntOrNull()?.let { offer.collections.getOrNull(it) } ?: return
        api.editMessage(chatId, offer.messageId, "'$name'을(를) '$collection' 컬렉션에 색인하는 중…")
        scope.launch {
            val result = try {
                files.addToCollection(binding.user, collection, offer.attachment.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Telegram 파일 색인 실패" }
                "색인 실패: ${e.message}"
            }
            runCatching { api.editMessage(chatId, offer.messageId, "'$name' → '$collection': $result") }
        }
    }

    /** 파일 본문을 붙인 질문. 일반 대화로 보낸다 (파일 내용이 웹 검색어로 나가지 않게) */
    private suspend fun withFile(chatId: Long, attachment: Attachment, question: String): String? {
        val content = try {
            files.readText(attachment.path, FILE_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(chatId, "파일을 읽지 못했습니다: ${e.message}")
            return null
        }
        return "/chat 첨부 파일 \"${attachment.name}\"의 내용입니다.\n\n<<<\n$content\n>>>\n\n$question"
    }

    private suspend fun send(chatId: Long, text: String) {
        api.sendMessage(chatId, text)
    }

    companion object {
        const val POLL_SECONDS = 50
        /** 이보다 오래된 메시지는 실행하지 않는다 */
        const val STALE_SECONDS = 600L
        const val MAX_PAIRING_FAILURES = 5
        const val APPROVAL_BODY_LIMIT = 3_000
        const val FILE_CHARS = 30_000
        private const val MAX_COLLECTION_BUTTONS = 6
        private const val MAX_OFFERS = 10

        /** 로컬 데이터를 답변에 쓰는 에이전트 */
        private val LOCAL_AGENTS = setOf(AgentType.DOCS, AgentType.CODE, AgentType.TASK, AgentType.PERSONAL)

        val COMMANDS = listOf(
            "new" to "새 대화",
            "web" to "웹 검색해서 답하기",
            "docs" to "등록한 문서에서 찾아 답하기",
            "chat" to "일반 대화",
            "cancel" to "답변 멈추기",
            "status" to "Vesta 상태",
            "help" to "사용법",
        )

        val HELP = """
            Vesta 사용법
            • 그냥 물어보면 알맞은 도우미가 답합니다.
            • /web 질문 — 웹 검색
            • /docs 질문, @컬렉션 질문 — 문서 검색
            • /chat 질문 — 일반 대화
            • 문서 파일(.txt .md .pdf)을 보내면 그 파일에 대해 묻거나 컬렉션에 추가할 수 있습니다.
            • /new 새 대화 · /cancel 답변 멈춤 · /status 상태
        """.trimIndent()
    }
}
