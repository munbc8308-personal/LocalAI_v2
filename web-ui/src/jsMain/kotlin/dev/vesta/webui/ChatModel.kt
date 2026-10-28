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
import dev.vesta.api.JobDto
import dev.vesta.api.MessageDto
import dev.vesta.api.RoutingDto
import dev.vesta.api.SessionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/** 답변에서 만든 이미지·영상 (M24) */
data class Generated(val src: String, val video: Boolean)

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
    val generated = mutableStateListOf<Generated>()
    /** 백그라운드로 넘긴 작업의 지금 상태 (M24 영상). 끝나면 결과 글·영상을 이 답 아래에 붙인다 */
    var job by mutableStateOf<JobDto?>(null)
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

    /** 지난 대화 찾기 (M30): 검색어가 있으면 목록 대신 맞은 대화 */
    var query by mutableStateOf("")
    val hits = mutableStateListOf<dev.vesta.api.SessionHitDto>()
    private var searching: Job? = null

    fun search(text: String) {
        query = text
        searching?.cancel()
        val q = text.trim()
        if (q.length < 2) { hits.clear(); return }
        searching = scope.launch {
            kotlinx.coroutines.delay(250)
            runCatching { Http.get("/api/sessions/search?q=" + js("encodeURIComponent")(q) as String, ListSerializer(dev.vesta.api.SessionHitDto.serializer())) }
                .onSuccess { hits.clear(); hits.addAll(it) }
                .onFailure { loadError = "찾지 못했습니다: ${it.message}" }
        }
    }

    fun refreshSessions() = scope.launch {
        runCatching { Http.get("/api/sessions?limit=50", ListSerializer(SessionDto.serializer())) }
            .onSuccess { sessions.clear(); sessions.addAll(it) }
    }

    /** 음성 대화(웹소켓)가 알아들은 말로 차례 하나를 만든다. 답 글은 음성 대화가 채운다 (M37) */
    fun voiceTurn(question: String): Turn = Turn(question).also { turns += it }

    /** 지금 대화의 지침 (C-06, M34 Q-03). 대화 목록에서 읽는다 */
    val prompt: String? get() = sessions.firstOrNull { it.id == sessionId }?.prompt

    /** 지금 대화의 지침을 바꾼다. 빈 글이면 지운다 */
    fun setPrompt(text: String) = scope.launch {
        val id = sessionId ?: return@launch
        runCatching { Http.put("/api/sessions/$id/prompt", ApiJson.encodeToString(dev.vesta.api.SessionPromptDto.serializer(), dev.vesta.api.SessionPromptDto(text.trim().ifEmpty { null }))) }
            .onSuccess { refreshSessions() }
            .onFailure { loadError = "지침을 저장하지 못했습니다: ${it.message}" }
    }

    /** 대화 하나 지우기 (붙은 이미지·영상까지). 지금 보던 대화면 새 대화로 */
    fun deleteSession(id: String) = scope.launch {
        runCatching { Http.delete("/api/sessions/$id") }
            .onSuccess {
                if (id == sessionId) newChat()
                sessions.removeAll { it.id == id }
            }
            .onFailure { loadError = "지우지 못했습니다: ${it.message}" }
    }

    /** 목록에 보이는 대화를 모두 지우기 */
    fun deleteAllSessions() = scope.launch {
        newChat()
        val failed = sessions.toList().count { s -> runCatching { Http.delete("/api/sessions/${s.id}") }.isFailure }
        if (failed > 0) loadError = "${failed}개를 지우지 못했습니다"
        refreshSessions()
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
    fun send(text: String, spoken: Boolean = false): Turn? {
        val images = pendingImages.map { it.dataUrl }
        if (busy || (text.isBlank() && images.isEmpty())) return null
        val turn = Turn(text.trim(), images)
        pendingImages.clear()
        turns += turn
        busy = true
        job = scope.launch {
            try {
                Http.chat(ChatRequestDto(turn.question, sessionId, images = images.map { it.substringAfter("base64,") }, spoken = spoken)).collect { apply(turn, it) }
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
            is EventDto.Media -> turn.generated += Generated("/api/media/${event.id}", event.mime.startsWith("video/"))
            is EventDto.Job -> follow(turn, event)
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

    /** 백그라운드 작업을 3초마다 본다. 끝나면 결과를 답 아래에 붙이고 그만 본다 (화면을 떠나도 결과는 대화에 남는다) */
    private fun follow(turn: Turn, event: EventDto.Job) {
        turn.job = JobDto(event.id, event.title, "QUEUED")
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(3_000)
                val state = runCatching { Http.get("/api/jobs/${event.id}", JobDto.serializer()) }.getOrElse {
                    // 서버가 다시 켜져 작업을 잃었으면(404) 그만 본다
                    if ((it as? HttpError)?.status == 404) { turn.job = turn.job?.copy(status = "FAILED", text = "작업을 찾을 수 없습니다 (서버가 다시 켜졌을 수 있음).") ; break }
                    continue
                }
                turn.job = state
                if (state.status == "DONE" || state.status == "FAILED") {
                    state.mediaId?.let { turn.generated += Generated("/api/media/$it", state.mime == "video/mp4") }
                    state.text?.let { turn.answer += "\n\n---\n\n$it" }
                    break
                }
            }
        }
    }

    private fun history(messages: List<MessageDto>): List<Turn> {
        val out = mutableListOf<Turn>()
        for (m in messages) when (m.role) {
            "USER" -> out += Turn(m.content, m.attachments.filter { it.mime.startsWith("image/") }.map { "/api/media/${it.id}" })
            "ASSISTANT" -> {
                // 백그라운드 작업 결과(같은 담당의 두 번째 답)는 앞 답 아래에 붙인다 (M24)
                val previous = out.lastOrNull()
                if (previous != null && previous.answer.isNotEmpty() && m.agent == "VIDEO" && previous.agent == "VIDEO" && previous.generated.isEmpty()) {
                    previous.answer += "\n\n---\n\n" + m.content
                    previous.generated.addAll(m.attachments.filter { it.mime == "video/mp4" || it.mime.startsWith("image/") }.map { Generated("/api/media/${it.id}", it.mime == "video/mp4") })
                    continue
                }
                val turn = out.lastOrNull()?.takeIf { it.answer.isEmpty() } ?: Turn("").also { out += it }
                turn.answer = m.content
                turn.agent = m.agent
                turn.interrupted = m.interrupted
                turn.generated.addAll(m.attachments.filter { it.mime.startsWith("image/") || it.mime == "video/mp4" }.map { Generated("/api/media/${it.id}", it.mime == "video/mp4") })
                turn.messageId = m.id.takeIf { it > 0 }
                turn.rating = m.rating ?: 0
                turn.correctAgent = m.correctAgent
            }
        }
        return out
    }
}
