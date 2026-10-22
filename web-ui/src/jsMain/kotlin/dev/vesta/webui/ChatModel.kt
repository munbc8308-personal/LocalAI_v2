package dev.vesta.webui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vesta.api.ApiJson
import dev.vesta.api.ApprovalDto
import dev.vesta.api.ChatRequestDto
import dev.vesta.api.EventDto
import dev.vesta.api.EvidenceDto
import dev.vesta.api.FeedbackDto
import dev.vesta.api.MessageDto
import dev.vesta.api.RoutingDto
import dev.vesta.api.SessionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/** 질문 하나와 그 답변 (화면의 한 묶음). [images]는 질문에 붙인 이미지 주소 (data: 또는 /api/media/…) */
class Turn(val question: String, val images: List<String> = emptyList()) {
    var answer by mutableStateOf("")
    var routing by mutableStateOf<RoutingDto?>(null)
    var status by mutableStateOf<String?>(null)
    var thinking by mutableStateOf(false)
    val tools = mutableStateListOf<ToolLine>()
    var sources by mutableStateOf(emptyList<EvidenceDto>())
    val approvals = mutableStateListOf<ApprovalView>()
    var grounding by mutableStateOf<EventDto.Grounding?>(null)
    var done by mutableStateOf<EventDto.Done?>(null)
    var error by mutableStateOf<String?>(null)
    /** 저장된 기록에서 불러온 답변 */
    var agent by mutableStateOf<String?>(null)
    var interrupted by mutableStateOf(false)
    /** 이 답변에서 만든 이미지 주소 (/api/media/…) */
    val generated = mutableStateListOf<String>()
    /** 저장된 답변 ID와 남긴 피드백 (C-09) */
    var messageId by mutableStateOf<Long?>(null)
    var rating by mutableStateOf(0)
    var correctAgent by mutableStateOf<String?>(null)
}

class ToolLine(val callId: String, val tool: String, val summary: String) {
    var ok by mutableStateOf<Boolean?>(null)
    var result by mutableStateOf<String?>(null)
}

enum class ApprovalState { PENDING, SENDING, APPROVED, EDITED, DENIED, EXPIRED }

class ApprovalView(val request: EventDto.Approval) {
    var state by mutableStateOf(ApprovalState.PENDING)
    var edited by mutableStateOf(request.editableValue.orEmpty())
}

/** 대화 화면 상태와 동작. 한 번에 질문 하나만 보낸다. */
class ChatController(private val scope: CoroutineScope) {
    val turns = mutableStateListOf<Turn>()
    val sessions = mutableStateListOf<SessionDto>()
    var sessionId by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    /** 다음 질문에 붙일 이미지 (V-04) */
    val pendingImages = mutableStateListOf<PendingImage>()
    private var job: Job? = null

    fun refreshSessions() = scope.launch {
        runCatching { Http.get("/api/sessions?limit=50", ListSerializer(SessionDto.serializer())) }
            .onSuccess { sessions.clear(); sessions.addAll(it) }
    }

    fun newChat() {
        stop()
        sessionId = null
        turns.clear()
        pendingImages.clear()
        loadError = null
    }

    fun open(id: String) = scope.launch {
        stop()
        loadError = null
        runCatching { Http.get("/api/sessions/$id/messages", ListSerializer(MessageDto.serializer())) }
            .onSuccess { messages ->
                sessionId = id
                turns.clear()
                turns.addAll(history(messages))
            }
            .onFailure { loadError = it.message }
    }

    /** 질문을 보낸다. 보냈으면 그 묶음(음성 대화가 답을 따라 읽는다), 못 보냈으면 null */
    fun send(text: String): Turn? {
        val images = pendingImages.map { it.dataUrl }
        if (busy || (text.isBlank() && images.isEmpty())) return null
        val turn = Turn(text.trim(), images)
        pendingImages.clear()
        turns += turn
        busy = true
        job = scope.launch {
            try {
                Http.chat(ChatRequestDto(turn.question, sessionId, images = images.map { it.substringAfter("base64,") })).collect { apply(turn, it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                turn.error = "중지함"
                throw e
            } catch (e: Throwable) {
                turn.error = e.message ?: "연결이 끊겼습니다"
            } finally {
                turn.status = null
                turn.thinking = false
                busy = false
            }
        }
        return turn
    }

    /** 👍/👎·담당 정정. 같은 단추를 다시 누르면 평가를 지운다 */
    fun feedback(turn: Turn, rating: Int, correctAgent: String? = turn.correctAgent) = scope.launch {
        val id = turn.messageId ?: return@launch
        val previous = turn.rating to turn.correctAgent
        turn.rating = rating
        turn.correctAgent = correctAgent
        runCatching { Http.post("/api/messages/$id/feedback", ApiJson.encodeToString(FeedbackDto.serializer(), FeedbackDto(rating, correctAgent))) }
            .onFailure { turn.rating = previous.first; turn.correctAgent = previous.second }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun decide(view: ApprovalView, decision: String) = scope.launch {
        view.state = ApprovalState.SENDING
        val dto = when (decision) {
            "edit" -> ApprovalDto("edit", edited = mapOf(view.request.editableField.orEmpty() to view.edited))
            "deny" -> ApprovalDto("deny", reason = "웹 UI에서 거부함")
            else -> ApprovalDto("approve")
        }
        view.state = runCatching { Http.post("/api/approvals/${view.request.requestId}", ApiJson.encodeToString(ApprovalDto.serializer(), dto)) }
            .fold(
                { if (decision == "deny") ApprovalState.DENIED else if (decision == "edit") ApprovalState.EDITED else ApprovalState.APPROVED },
                { if ((it as? HttpError)?.status == 404) ApprovalState.EXPIRED else ApprovalState.PENDING },
            )
    }

    private fun apply(turn: Turn, event: EventDto) {
        when (event) {
            is EventDto.Session -> {
                sessionId = event.sessionId
                if (event.created) refreshSessions()
            }
            is EventDto.Routed -> turn.routing = event.routing
            is EventDto.Status -> turn.status = event.text
            is EventDto.Reasoning -> turn.thinking = true
            is EventDto.Sources -> turn.sources = event.evidence
            is EventDto.ToolStarted -> turn.tools += ToolLine(event.callId, event.tool, event.summary)
            is EventDto.ToolFinished -> turn.tools.lastOrNull { it.callId == event.callId }?.let { it.ok = event.ok; it.result = event.summary }
            is EventDto.Approval -> turn.approvals += ApprovalView(event)
            is EventDto.Grounding -> turn.grounding = event
            is EventDto.Media -> turn.generated += "/api/media/${event.id}"
            is EventDto.Saved -> turn.messageId = event.messageId
            is EventDto.Token -> {
                turn.thinking = false
                turn.status = null
                turn.answer += event.text
            }
            is EventDto.Done -> turn.done = event
            is EventDto.Error -> turn.error = event.message
        }
    }

    private fun history(messages: List<MessageDto>): List<Turn> {
        val out = mutableListOf<Turn>()
        for (m in messages) when (m.role) {
            "USER" -> out += Turn(m.content, m.attachments.filter { it.mime.startsWith("image/") }.map { "/api/media/${it.id}" })
            "ASSISTANT" -> {
                val turn = out.lastOrNull()?.takeIf { it.answer.isEmpty() } ?: Turn("").also { out += it }
                turn.answer = m.content
                turn.agent = m.agent
                turn.interrupted = m.interrupted
                turn.generated.addAll(m.attachments.filter { it.mime.startsWith("image/") }.map { "/api/media/${it.id}" })
                turn.messageId = m.id.takeIf { it > 0 }
                turn.rating = m.rating ?: 0
                turn.correctAgent = m.correctAgent
            }
        }
        return out
    }
}
