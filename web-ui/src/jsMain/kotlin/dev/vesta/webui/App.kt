package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.vesta.api.ApiJson
import dev.vesta.api.CollectionDto
import dev.vesta.api.DashboardDto
import dev.vesta.api.LoginDto
import dev.vesta.api.ActivateDto
import dev.vesta.api.MeDto
import dev.vesta.api.EvolutionStateDto
import dev.vesta.api.ProposalResultDto
import dev.vesta.api.TrainingStateDto
import dev.vesta.api.MediaItemDto
import dev.vesta.api.RoutingDto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoFocus
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement

enum class Tab(val label: String) { CHAT("대화"), GALLERY("갤러리"), COLLECTIONS("컬렉션"), DASHBOARD("상태") }

@Composable
fun App() {
    var me by remember { mutableStateOf<MeDto?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        me = runCatching { Http.get("/api/me", MeDto.serializer()) }.getOrNull()
        Http.csrf = me?.csrf
        checked = true
    }
    when {
        !checked -> Div({ classes("center", "muted") }) { Text("불러오는 중…") }
        me == null -> LoginView { me = it; Http.csrf = it.csrf }
        else -> Main(me!!) { me = null; Http.csrf = null }
    }
}

@Composable
private fun LoginView(onLogin: (MeDto) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    Div({ classes("center") }) {
        Form(attrs = {
            classes("card", "login")
            addEventListener("submit") { event ->
                event.preventDefault()
                if (sending || password.isEmpty()) return@addEventListener
                sending = true
                scope.launch {
                    runCatching { Http.post("/api/login", ApiJson.encodeToString(LoginDto.serializer(), LoginDto(password, name.trim().ifEmpty { null })), MeDto.serializer()) }
                        .onSuccess(onLogin)
                        .onFailure { error = it.message; password = "" }
                    sending = false
                }
            }
        }) {
            H2 { Text("Vesta") }
            P({ classes("muted") }) { Text("이 Mac의 로컬 AI 어시스턴트") }
            // 비워 두면 Mac 주인 (가족·팀은 받은 이름으로)
            Input(InputType.Text) {
                value(name)
                onInput { name = it.value }
                placeholder("이름 (Mac 주인은 비워 두기)")
                attr("autocomplete", "username")
                attr("autocapitalize", "off")
            }
            Input(InputType.Password) {
                value(password)
                onInput { password = it.value }
                placeholder("비밀번호")
                attr("autocomplete", "current-password")
                autoFocus()
            }
            error?.let { P({ classes("error") }) { Text(it) } }
            Button({ type(ButtonType.Submit); classes("primary"); if (sending) disabled() }) { Text(if (sending) "확인 중…" else "로그인") }
        }
    }
}

@Composable
private fun Main(me: MeDto, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chat = remember { ChatController(scope) }
    var tab by remember { mutableStateOf(Tab.CHAT) }
    var drawer by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { chat.refreshSessions() }

    Div({ classes("app") }) {
        Div({ classes("topbar") }) {
            if (tab == Tab.CHAT) Button({ classes("icon"); attr("aria-label", "대화 목록"); onClick { drawer = !drawer } }) { Text("☰") }
            Span({ classes("brand") }) { Text("Vesta") }
            Div({ classes("tabs") }) {
                Tab.entries.forEach { t ->
                    Button({ classes("tab"); if (t == tab) classes("active"); onClick { tab = t; drawer = false } }) { Text(t.label) }
                }
            }
            Button({
                classes("link")
                onClick { scope.launch { runCatching { Http.post("/api/logout") }; onLogout() } }
            }) { Text("로그아웃") }
        }
        when (tab) {
            Tab.CHAT -> ChatView(chat, drawer, { drawer = it }, draft, { draft = it })
            Tab.GALLERY -> GalleryView()
            Tab.COLLECTIONS -> CollectionsView { name -> draft = "@$name "; tab = Tab.CHAT }
            Tab.DASHBOARD -> DashboardView(me)
        }
    }
}

@Composable
private fun ChatView(chat: ChatController, drawer: Boolean, setDrawer: (Boolean) -> Unit, draft: String, setDraft: (String) -> Unit) {
    Div({ classes("chat") }) {
        Div({ classes("sidebar"); if (drawer) classes("open") }) {
            Button({ classes("primary", "wide"); onClick { chat.newChat(); setDrawer(false) } }) { Text("+ 새 대화") }
            Ul({ classes("sessions") }) {
                chat.sessions.forEach { s ->
                    Li({
                        classes("session")
                        if (s.id == chat.sessionId) classes("active")
                        onClick { chat.open(s.id); setDrawer(false) }
                    }) {
                        Div { Text(s.title ?: "(제목 없음)") }
                        Div({ classes("muted", "small") }) { Text("${channelName(s.channel)} · ${localTime(s.updatedAt)}") }
                    }
                }
            }
        }
        if (drawer) Div({ classes("scrim"); onClick { setDrawer(false) } })
        Div({ classes("conversation") }) {
            Div({ id("messages"); classes("messages") }) {
                if (chat.turns.isEmpty()) {
                    Div({ classes("empty") }) {
                        P { Text("무엇이든 물어보세요.") }
                        P({ classes("muted", "small") }) { Text("/web 웹 검색 · /docs 문서 · @컬렉션 이름 · /chat 일반 대화 · /image 이미지 만들기 · 🎤 음성 · 📎 이미지(붙여넣기·끌어 놓기)") }
                    }
                }
                chat.loadError?.let { P({ classes("error") }) { Text(it) } }
                chat.turns.forEach { TurnView(chat, it) }
            }
            Composer(chat, draft, setDraft)
        }
    }
    // 새 질문·도구·승인 요청이 오면 맨 아래로, 답변 조각은 맨 아래 근처에 있을 때만 따라 내려간다
    val last = chat.turns.lastOrNull()
    LaunchedEffect(chat.turns.size, last?.approvals?.size, last?.tools?.size) {
        (document.getElementById("messages") as? HTMLElement)?.let { it.scrollTop = it.scrollHeight.toDouble() }
    }
    LaunchedEffect(last?.answer?.length) {
        val box = document.getElementById("messages") as? HTMLElement ?: return@LaunchedEffect
        if (box.scrollHeight - box.scrollTop - box.clientHeight < 200) box.scrollTop = box.scrollHeight.toDouble()
    }
}

@Composable
private fun Composer(chat: ChatController, draft: String, setDraft: (String) -> Unit) {
    // 터치 기기에서는 Enter가 줄바꿈, 보내기는 버튼
    val touch = remember { window.matchMedia("(pointer: coarse)").matches }
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder() }
    // 음성 대화 모드 (M18): 손을 대지 않고 말 → 답 → 읽어 주기를 되풀이
    val voice = remember { VoiceConversation(scope, chat) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    // 이벤트 핸들러가 이전 화면의 draft 값을 붙잡고 있을 수 있어 입력란에서 직접 읽는다
    fun submit() {
        val area = document.getElementById("composer") as? HTMLTextAreaElement ?: return
        val text = area.value
        if (chat.busy || (text.isBlank() && chat.pendingImages.isEmpty())) return
        chat.send(text)
        setDraft("")
        area.value = ""
        area.style.height = ""
    }
    fun addImages(files: dynamic) {
        val count: Int = (files?.length ?: 0).unsafeCast<Int>()
        // FileList는 입력란을 비우면 같이 비므로 먼저 옮겨 둔다
        val picked = (0 until count).map { files.item(it).unsafeCast<org.w3c.files.File>() }
        scope.launch {
            for (file in picked) {
                if (!file.type.startsWith("image/")) continue
                if (chat.pendingImages.size >= MAX_IMAGES) { mediaError = "이미지는 ${MAX_IMAGES}장까지 붙일 수 있습니다"; break }
                runCatching { Images.prepare(file) }.onSuccess { chat.pendingImages += it; mediaError = null }.onFailure { mediaError = it.message }
            }
        }
    }
    // 녹음 → 인식한 글을 입력란에 넣는다. 확인하고 보내기를 누른다 (V-02)
    fun toggleRecording() = scope.launch {
        mediaError = null
        if (!recording) {
            runCatching { recorder.start() }.onSuccess { recording = true }.onFailure { mediaError = "마이크를 열지 못했습니다: ${it.message}" }
            return@launch
        }
        recording = false
        transcribing = true
        try {
            val text = MediaApi.transcribe(recorder.stop())
            if (text.isBlank()) mediaError = "알아듣지 못했습니다. 다시 말해 주세요"
            else {
                val area = document.getElementById("composer") as? HTMLTextAreaElement
                val joined = listOf(area?.value.orEmpty(), text).filter { it.isNotBlank() }.joinToString(" ")
                area?.value = joined
                setDraft(joined)
                area?.focus()
            }
        } catch (e: Throwable) {
            mediaError = e.message
        } finally {
            transcribing = false
        }
    }
    if (voice.active || voice.error != null) VoiceBar(voice)
    if (chat.pendingImages.isNotEmpty() || mediaError != null) Div({ classes("attachments") }) {
        chat.pendingImages.forEach { image ->
            Div({ classes("thumb") }) {
                Img(image.dataUrl, "붙인 이미지")
                Button({ classes("remove"); attr("aria-label", "빼기"); onClick { chat.pendingImages.remove(image) } }) { Text("×") }
            }
        }
        mediaError?.let { Span({ classes("error", "small") }) { Text(it) } }
    }
    Div({
        classes("composer")
        // 이미지를 끌어다 놓기
        onDragOver { it.preventDefault() }
        onDrop { event ->
            event.preventDefault()
            addImages(event.nativeEvent.asDynamic().dataTransfer?.files)
        }
    }) {
        Input(InputType.File) {
            id("image-input")
            attr("accept", "image/*")
            attr("multiple", "")
            style { property("display", "none") }
            onChange { event ->
                val input = event.target.asDynamic()
                addImages(input.files)
                input.value = ""
            }
        }
        Button({
            classes("icon")
            attr("title", "이미지 붙이기")
            attr("aria-label", "이미지 붙이기")
            onClick { (document.getElementById("image-input") as? HTMLElement)?.click() }
        }) { Text("📎") }
        Button({
            classes("icon")
            if (recording) classes("recording")
            attr("title", if (recording) "녹음 끝내기" else "음성으로 입력")
            attr("aria-label", if (recording) "녹음 끝내기" else "음성으로 입력")
            if (transcribing) disabled()
            onClick { toggleRecording() }
        }) { Text(if (transcribing) "…" else if (recording) "■" else "🎤") }
        Button({
            classes("icon")
            if (voice.active) classes("recording")
            attr("title", if (voice.active) "음성 대화 끝내기" else "음성 대화 (손 안 대고 말하고 듣기)")
            attr("aria-label", if (voice.active) "음성 대화 끝내기" else "음성 대화")
            if (recording || transcribing) disabled()
            onClick {
                if (voice.active) voice.stop()
                else scope.launch { runCatching { Speaker.stop(); voice.start() }.onFailure { voice.error = "마이크를 열지 못했습니다: ${it.message}" } }
            }
        }) { Text("🗣") }
        TextArea(draft) {
            id("composer")
            attr("rows", "1")
            placeholder(if (recording) "녹음 중… ■를 누르면 끝" else if (transcribing) "음성 인식 중…" else "메시지")
            // 스크린샷 붙여넣기
            onPaste { event ->
                val files = event.nativeEvent.asDynamic().clipboardData?.files
                if ((files?.length ?: 0).unsafeCast<Int>() > 0) {
                    event.preventDefault()
                    addImages(files)
                }
            }
            onInput { event ->
                setDraft(event.value)
                val area = event.target
                area.style.height = "auto"
                area.style.height = "${minOf(area.scrollHeight, 200)}px"
            }
            onKeyDown { event ->
                // 한글 조합 중 Enter는 글자 확정이므로 보내지 않는다
                val composing = event.nativeEvent.asDynamic().isComposing == true || event.nativeEvent.asDynamic().keyCode == 229
                if (event.key == "Enter" && !event.shiftKey && !composing && !touch) {
                    event.preventDefault()
                    submit()
                }
            }
        }
        if (chat.busy) Button({ classes("stop"); onClick { chat.stop() } }) { Text("중지") }
        else Button({ classes("primary"); onClick { submit() } }) { Text("보내기") }
    }
}

@Composable
private fun TurnView(chat: ChatController, turn: Turn) {
    if (turn.question.isNotEmpty() || turn.images.isNotEmpty()) Div({ classes("bubble", "user") }) {
        if (turn.images.isNotEmpty()) Div({ classes("bubble-images") }) { turn.images.forEach { Img(it, "질문에 붙인 이미지") } }
        if (turn.question.isNotEmpty()) Text(turn.question)
    }
    Div({ classes("bubble", "assistant") }) {
        val agent = turn.routing?.agent ?: turn.agent
        if (agent != null) Div({ classes("meta") }) {
            Span({ classes("chip") }) { Text(agentName(agent)) }
            turn.routing?.let { Span({ classes("muted", "small") }) { Text(stageName(it)) } }
        }
        turn.tools.forEach { tool ->
            Div({ classes("tool") }) {
                Span({ classes(if (tool.ok == false) "bad" else if (tool.ok == true) "good" else "muted") }) {
                    Text(when (tool.ok) { null -> "◌"; true -> "✓"; false -> "✗" })
                }
                Span { Text(" ${tool.summary}") }
                tool.result?.takeIf { it.isNotBlank() && tool.ok == false }?.let { Div({ classes("muted", "small") }) { Text(it) } }
            }
        }
        turn.approvals.forEach { ApprovalCard(chat, it) }
        // 만든 이미지: 누르면 원본을 새 탭에서
        if (turn.generated.isNotEmpty()) Div({ classes("generated") }) {
            turn.generated.forEach { src -> A(src, { target(ATarget.Blank); attr("rel", "noopener") }) { Img(src, "만든 이미지") } }
        }
        if (turn.answer.isNotEmpty()) Div({ classes("answer") }) { Markdown(turn.answer) }
        if (turn.answer.isEmpty() && turn.error == null && turn.done == null && chat.busy && turn === chat.turns.lastOrNull()) {
            Div({ classes("muted", "pulse") }) { Text(turn.status ?: if (turn.thinking) "생각 중…" else "준비 중…") }
        }
        if (turn.sources.isNotEmpty()) Div({ classes("sources") }) {
            // 답변이 끝나면 실제로 인용한 번호만 남긴다 (인용이 하나도 없으면 전부)
            val cited = if (turn.done != null) citedNumbers(turn.answer) else emptySet()
            turn.sources.filter { it.citation != null && (cited.isEmpty() || it.citation in cited) }.forEach { e ->
                Div({ classes("source") }) {
                    Span({ classes("cite") }) { Text("[${e.citation}]") }
                    if (e.ref.startsWith("http://") || e.ref.startsWith("https://")) {
                        A(e.ref, { target(ATarget.Blank); attr("rel", "noopener noreferrer") }) { Text(" ${e.title}") }
                    } else Span { Text(" ${e.title}") }
                    e.location?.let { Span({ classes("muted") }) { Text(" · $it") } }
                }
            }
        }
        turn.grounding?.takeIf { it.unsupported.isNotEmpty() }?.let { g ->
            var open by remember { mutableStateOf(false) }
            Div({ classes("warn"); onClick { open = !open } }) {
                Text("⚠ 근거로 확인되지 않은 문장 ${g.unsupported.size}개 (${g.checked}개 중)")
                if (open) Ul { g.unsupported.forEach { Li { Text(it) } } }
            }
        }
        turn.error?.let { Div({ classes("error") }) { Text(it) } }
        if (turn.interrupted) Div({ classes("muted", "small") }) { Text("(중간에 멈춘 답변)") }
        turn.done?.let { d ->
            Div({ classes("muted", "small") }) {
                Text(listOfNotNull(d.model, d.completionTokens?.let { "$it 토큰" }).joinToString(" · "))
            }
        }
        // 읽어 주기 (V-03): 코드·표·출처는 서버가 빼고 읽는다
        if (turn.answer.isNotBlank() && !(chat.busy && turn === chat.turns.lastOrNull())) Div({ classes("turn-actions") }) {
            SpeakButton(turn)
            if (turn.messageId != null) FeedbackButtons(chat, turn)
        }
    }
}

/** 만든 이미지 모음 (V-08). 최근 것부터 */
@Composable
private fun GalleryView() {
    var items by remember { mutableStateOf<List<MediaItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { Http.get("/api/media?kind=GENERATED&limit=100", ListSerializer(MediaItemDto.serializer())) }
            .onSuccess { items = it }
            .onFailure { error = it.message }
    }
    Div({ classes("page") }) {
        H2 { Text("갤러리") }
        error?.let { P({ classes("error") }) { Text(it) } }
        val list = items
        when {
            list == null && error == null -> P({ classes("muted") }) { Text("불러오는 중…") }
            list != null && list.isEmpty() -> P({ classes("muted") }) { Text("아직 만든 이미지가 없습니다. 대화에서 /image 고양이 수채화 처럼 요청해 보세요.") }
            list != null -> Div({ classes("gallery") }) {
                list.forEach { item ->
                    Div({ classes("card") }) {
                        A("/api/media/${item.id}", { target(ATarget.Blank); attr("rel", "noopener") }) { Img("/api/media/${item.id}", item.caption ?: "만든 이미지") }
                        item.caption?.let { P({ classes("muted", "small") }) { Text(it) } }
                        item.createdAt?.let { P({ classes("muted", "small") }) { Text(localTime(it)) } }
                    }
                }
            }
        }
    }
}

/** 👍/👎와 담당 정정 (C-09). 정정은 결정 모델 학습 후보가 된다 */
@Composable
private fun FeedbackButtons(chat: ChatController, turn: Turn) {
    Button({
        classes("link", "small")
        if (turn.rating == 1) classes("on")
        attr("aria-label", "좋아요")
        onClick { chat.feedback(turn, if (turn.rating == 1) 0 else 1) }
    }) { Text("👍") }
    Button({
        classes("link", "small")
        if (turn.rating == -1) classes("on")
        attr("aria-label", "별로예요")
        onClick { chat.feedback(turn, if (turn.rating == -1) 0 else -1) }
    }) { Text("👎") }
    // 맡은 담당자가 틀렸으면 고른다 (라우팅 정정)
    Select({
        classes("small")
        attr("aria-label", "담당 정정")
        onChange { event -> chat.feedback(turn, turn.rating, event.value?.ifEmpty { null }) }
    }) {
        Option("", { if (turn.correctAgent == null) selected() }) { Text("담당 정정…") }
        listOf("CHAT", "DOCS", "WEB", "CODE", "TASK", "PERSONAL", "IMAGE").forEach { agent ->
            Option(agent, { if (turn.correctAgent == agent) selected() }) { Text("→ " + agentName(agent)) }
        }
    }
    turn.correctAgent?.let { Span({ classes("muted", "small") }) { Text(" 정정을 저장했습니다 (다음 학습에 반영)") } }
}

/** 음성 대화 상태 줄: 지금 단계, 마이크 크기, 끊기·끝내기 */
@Composable
private fun VoiceBar(voice: VoiceConversation) {
    Div({ classes("voicebar") }) {
        val label = when (voice.phase) {
            VoicePhase.LISTENING -> "듣는 중… 말씀하세요"
            VoicePhase.HEARING -> "말씀 듣는 중…"
            VoicePhase.THINKING -> "생각 중…"
            VoicePhase.SPEAKING -> "말하는 중 (눌러서 끊기)"
            VoicePhase.OFF -> "음성 대화 꺼짐"
        }
        Button({
            classes("link", "small")
            onClick { voice.interrupt() }
            if (voice.phase != VoicePhase.SPEAKING && voice.phase != VoicePhase.THINKING) disabled()
        }) { Text(label) }
        Div({ classes("bar", "level") }) { Div({ classes("fill"); style { property("width", "${(voice.level * 100).toInt()}%") } }) }
        voice.heard?.let { Span({ classes("muted", "small") }) { Text("“${it.take(60)}”") } }
        voice.error?.let { Span({ classes("error", "small") }) { Text(it) } }
        if (voice.active) Button({ classes("link", "small"); onClick { voice.stop() } }) { Text("끝내기") }
    }
}

/** 한 번에 답변 하나만 읽는다 */
private object Speaker {
    var playing by mutableStateOf<Turn?>(null)
    var loading by mutableStateOf<Turn?>(null)
    private var audio: dynamic = null

    fun stop() {
        audio?.pause()
        audio = null
        playing = null
    }

    suspend fun play(turn: Turn) {
        stop()
        loading = turn
        val url = try {
            MediaApi.speech(turn.answer)
        } finally {
            loading = null
        }
        val player: dynamic = js("new Audio(url)")
        player.onended = { _: dynamic -> if (playing === turn) playing = null; org.w3c.dom.url.URL.revokeObjectURL(url) }
        audio = player
        playing = turn
        player.play()
    }
}

@Composable
private fun SpeakButton(turn: Turn) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val label = when {
        Speaker.loading === turn -> "🔊 준비 중…"
        Speaker.playing === turn -> "■ 멈춤"
        else -> "🔊 읽기"
    }
    Button({
        classes("link", "small")
        onClick {
            error = null
            if (Speaker.playing === turn) Speaker.stop()
            else scope.launch { runCatching { Speaker.play(turn) }.onFailure { error = it.message } }
        }
    }) { Text(label) }
    error?.let { Span({ classes("error", "small") }) { Text(" $it") } }
}

@Composable
private fun ApprovalCard(chat: ChatController, view: ApprovalView) {
    val r = view.request
    Div({ classes("approval", "risk-${r.risk.lowercase()}") }) {
        Div({ classes("approval-head") }) {
            Span({ classes("chip", "risk") }) { Text(riskName(r.risk)) }
            Span { Text(r.title) }
        }
        r.warning?.let { Div({ classes("error") }) { Text("⚠ $it") } }
        r.reason?.let { Div({ classes("muted", "small") }) { Text(it) } }
        if (r.editableField != null && view.state == ApprovalState.PENDING) {
            TextArea(view.edited) {
                classes("mono")
                attr("rows", "3")
                attr("spellcheck", "false")
                attr("autocapitalize", "off")
                onInput { view.edited = it.value }
            }
        } else Pre({ classes("mono") }) { Text(if (view.state == ApprovalState.EDITED) view.edited else r.body) }
        when (view.state) {
            ApprovalState.PENDING -> Div({ classes("actions") }) {
                val changed = r.editableField != null && view.edited != r.editableValue
                // 보낼 때 다시 계산한다: 화면을 그린 뒤 고친 명령이 원래 명령으로 승인되면 안 된다
                Button({
                    classes("primary")
                    onClick { chat.decide(view, if (r.editableField != null && view.edited != r.editableValue) "edit" else "approve") }
                }) { Text(if (changed) "수정해서 승인" else "승인") }
                Button({ classes("danger"); onClick { chat.decide(view, "deny") } }) { Text("거부") }
            }
            ApprovalState.SENDING -> Div({ classes("muted") }) { Text("보내는 중…") }
            ApprovalState.APPROVED -> Div({ classes("good") }) { Text("✓ 승인함") }
            ApprovalState.EDITED -> Div({ classes("good") }) { Text("✓ 수정해서 승인함") }
            ApprovalState.DENIED -> Div({ classes("bad") }) { Text("✗ 거부함") }
            ApprovalState.EXPIRED -> Div({ classes("muted") }) { Text("이미 처리되었거나 시간이 지났습니다") }
        }
    }
}

@Composable
private fun CollectionsView(onAsk: (String) -> Unit) {
    var list by remember { mutableStateOf<List<CollectionDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { Http.get("/api/collections", ListSerializer(CollectionDto.serializer())) }
            .onSuccess { list = it }.onFailure { error = it.message }
    }
    Div({ classes("page") }) {
        H2 { Text("컬렉션") }
        P({ classes("muted", "small") }) { Text("질문 앞에 @이름 을 붙이면 그 컬렉션에서만 찾습니다. 추가·색인은 이 Mac 터미널에서: vesta collection add / vesta repo add") }
        error?.let { P({ classes("error") }) { Text(it) } }
        val items = list
        when {
            items == null && error == null -> P({ classes("muted") }) { Text("불러오는 중…") }
            items != null && items.isEmpty() -> P({ classes("muted") }) { Text("아직 컬렉션이 없습니다") }
            items != null -> items.forEach { c ->
                Div({ classes("card", "clickable"); onClick { onAsk(c.name) } }) {
                    Div({ classes("row") }) {
                        Span({ classes("strong") }) { Text("@${c.name}") }
                        Span({ classes("chip") }) { Text(if (c.kind == "CODE") "코드" else "문서") }
                    }
                    c.description?.let { Div { Text(it) } }
                    Div({ classes("muted", "small") }) { Text("문서 ${c.documents}개 · 조각 ${c.chunks}개 · ${c.embeddingModel}") }
                    c.owner?.let { Div({ classes("muted", "small") }) { Text("$it 님이 공유함 (읽기 전용)") } }
                    if (c.sharedWith.isNotEmpty()) Div({ classes("muted", "small") }) { Text("공유: ${c.sharedWith.joinToString()}") }
                    c.rootPath?.let { Div({ classes("muted", "small", "mono") }) { Text(it) } }
                }
            }
        }
    }
}

@Composable
private fun DashboardView(me: MeDto) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<DashboardDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() = scope.launch {
        runCatching { Http.get("/api/dashboard", DashboardDto.serializer()) }
            .onSuccess { data = it; error = null }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { load() }
    Div({ classes("page") }) {
        Div({ classes("row") }) {
            H2 { Text("상태") }
            Button({ classes("link"); onClick { load() } }) { Text("새로 고침") }
        }
        error?.let { P({ classes("error") }) { Text(it) } }
        val d = data ?: return@Div
        Div({ classes("card") }) {
            d.checks.forEach { (name, value) ->
                val ok = value != "응답 없음" && value != "없음"
                Div({ classes("row") }) {
                    Span { Text(name) }
                    Span({ classes(if (ok) "good" else "bad") }) { Text(value) }
                }
            }
        }
        val used = d.loaded.sumOf { it.sizeBytes }
        Div({ classes("card") }) {
            Div({ classes("row") }) {
                Span { Text("메모리 (로드된 모델)") }
                Span { Text("${gb(used)} / 예산 ${gb(d.memoryBudgetBytes)} (전체 ${gb(d.memoryTotalBytes)})") }
            }
            Div({ classes("bar") }) {
                val percent = if (d.memoryBudgetBytes > 0) (used * 100 / d.memoryBudgetBytes).coerceAtMost(100) else 0
                Div({ classes("fill"); if (percent > 90) classes("over"); style { property("width", "$percent%") } })
            }
            if (d.loaded.isEmpty()) Div({ classes("muted", "small") }) { Text("지금 메모리에 올라간 모델 없음") }
            d.loaded.forEach { m -> Div({ classes("row", "small") }) { Span({ classes("mono") }) { Text(m.name) }; Span { Text(gb(m.sizeBytes)) } } }
        }
        Div({ classes("card") }) {
            d.models.forEach { (role, name) -> Div({ classes("row", "small") }) { Span { Text(role) }; Span({ classes("mono") }) { Text(name) } } }
        }
        if (me.role == "OWNER") EvolutionCard()
        if (me.role == "OWNER") TrainingCard()
        if (me.role == "OWNER") UsersCard(me)
        PasswordCard()
        P({ classes("muted", "small") }) { Text("Vesta ${d.version} · ${me.name} (${me.role})") }
    }
}

/** 주간 자가 개선 (M16, E-05·E-07): 마지막 점검 결과, 승인 요청 [승인]/[거절], 반영한 변경 [되돌리기]. 점검 중이면 10초마다 새로 읽는다 */
@Composable
private fun EvolutionCard() {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<EvolutionStateDto?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var showReport by remember { mutableStateOf(false) }
    fun load() = scope.launch {
        runCatching { Http.get("/api/evolution", EvolutionStateDto.serializer()) }
            .onSuccess { state = it; error = null }
            .onFailure { error = it.message }
    }
    fun act(id: Long, action: String) = scope.launch {
        runCatching { Http.post("/api/evolution/$id/$action", "{}", ProposalResultDto.serializer()) }
            .onSuccess { notice = it.message; error = null }
            .onFailure { error = it.message; notice = null }
        load()
    }
    LaunchedEffect(tick) {
        load()
        if (state?.lastRun?.status == "RUNNING" || state?.restartPending == true) {
            kotlinx.coroutines.delay(10_000)
            tick++
        }
    }
    val s = state ?: return
    Div({ classes("card") }) {
        Div({ classes("row") }) {
            Span { B { Text("주간 자가 개선") } }
            Span({ classes("muted", "small") }) { Text(if (s.enabled) "매주 일요일 03:00" else "꺼짐") }
        }
        val run = s.lastRun
        if (run == null) Div({ classes("muted", "small") }) { Text("아직 점검한 적이 없습니다 (vesta evolve run)") }
        else {
            Div({ classes("row", "small") }) {
                Span { Text("마지막 점검 #${run.id} · ${run.startedAt.take(16).replace('T', ' ')}") }
                Span { Text(when (run.status) { "RUNNING" -> "진행 중 · ${run.stage ?: ""}"; "DONE" -> "완료"; else -> "실패" }) }
            }
            run.error?.let { Div({ classes("error", "small") }) { Text(it) } }
            run.report?.let { report ->
                Button({ classes("link", "small"); onClick { showReport = !showReport } }) { Text(if (showReport) "보고서 접기" else "보고서 보기") }
                if (showReport) Div({ classes("small") }) { Markdown(report) }
            }
        }
        if (s.restartPending) Div({ classes("muted", "small") }) { Text("바뀐 설정을 쓰려고, 하던 답변이 끝나면 서버를 다시 시작합니다") }
        notice?.let { P({ classes("small") }) { Text(it) } }
        error?.let { P({ classes("error", "small") }) { Text(it) } }
        s.proposals.take(5).forEach { p ->
            Div({ classes("run") }) {
                Div({ classes("row", "small") }) {
                    Span { Text("#${p.id} ${p.title}") }
                    Span { Text(proposalStatus(p.status)) }
                }
                p.changes.forEach { (key, value) -> Div({ classes("muted", "small", "mono") }) { Text("$key: ${p.previous[key] ?: "(config.yaml)"} → $value") } }
                if (p.status == "PENDING") Div({ classes("muted", "small") }) { p.summary.lines().forEach { Div { Text(it) } } }
                Div({ classes("row", "small") }) {
                    Span {}
                    Span {
                        if (p.status == "PENDING") {
                            Button({ classes("primary", "small"); onClick { act(p.id, "approve") } }) { Text("승인") }
                            Button({ classes("link", "small"); onClick { act(p.id, "reject") } }) { Text("거절") }
                        }
                        if (p.status == "APPLIED") Button({ classes("link", "small"); onClick { act(p.id, "rollback") } }) { Text("되돌리기") }
                    }
                }
            }
        }
        if (s.tuned.isNotEmpty()) Div({ classes("muted", "small") }) { Text("덮어쓴 설정: " + s.tuned.entries.joinToString { "${it.key}=${it.value}" }) }
    }
}

private fun proposalStatus(status: String) = when (status) {
    "PENDING" -> "승인 대기"
    "APPLIED" -> "반영함"
    "REJECTED" -> "거절함"
    "ROLLED_BACK" -> "되돌림"
    else -> "만료"
}

/** 결정 모델 학습 (M14, L-05·L-07): 진행률·손실, 취소, 버전 전환·되돌리기. 진행 중이면 5초마다 새로 읽는다 */
@Composable
private fun TrainingCard() {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TrainingStateDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    fun load() = scope.launch {
        runCatching { Http.get("/api/training", TrainingStateDto.serializer()) }
            .onSuccess { state = it; error = null }
            .onFailure { error = it.message }
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { error = it.message }
        load()
    }
    LaunchedEffect(tick) {
        load()
        if (state?.runs?.any { it.status == "RUNNING" } != false) {
            kotlinx.coroutines.delay(5_000)
            tick++
        }
    }
    val s = state ?: return
    val running = s.runs.firstOrNull { it.status == "RUNNING" }
    Div({ classes("card") }) {
        Div({ classes("row") }) {
            Span { B { Text("결정 모델 학습") } }
            Button({
                classes("primary")
                if (running != null) disabled()
                onClick { act { Http.post("/api/training/decision"); tick++ } }
            }) { Text(if (running != null) "학습 중…" else "학습 시작") }
        }
        Div({ classes("row", "small") }) { Span { Text("지금 쓰는 모델") }; Span({ classes("mono") }) { Text(s.decisionModel) } }
        Div({ classes("row", "small") }) { Span { Text("다음 학습에 쓸 라우팅 정정") }; Span { Text("${s.corrections}개") } }
        error?.let { P({ classes("error", "small") }) { Text(it) } }
        s.runs.take(5).forEach { run ->
            Div({ classes("run") }) {
                Div({ classes("row", "small") }) {
                    Span { Text("r${run.id} · ${trainingStatus(run.status)} · ${run.stage ?: "-"}") }
                    Span {
                        run.loss?.let { Text("손실 ${(it * 1000).toInt() / 1000.0}  ") }
                        if (run.status == "RUNNING") Button({ classes("link", "small"); onClick { act { Http.post("/api/training/${run.id}/cancel") } } }) { Text("취소") }
                    }
                }
                if (run.status == "RUNNING" && run.total > 0) Div({ classes("bar") }) {
                    Div({ classes("fill"); style { property("width", "${run.step * 100 / run.total}%") } })
                }
                run.error?.let { Div({ classes("muted", "small") }) { Text(it) } }
                gateSummary(run.report)?.let { Div({ classes("muted", "small") }) { Text(it) } }
            }
        }
        if (s.versions.isNotEmpty()) {
            Div({ classes("small") }) { B { Text("버전") } }
            s.versions.forEach { v ->
                Div({ classes("row", "small") }) {
                    Span({ classes("mono") }) { Text((if (v.active) "● " else "○ ") + v.name) }
                    if (!v.active) Button({ classes("link", "small"); onClick { act { Http.post("/api/training/activate", ApiJson.encodeToString(ActivateDto.serializer(), ActivateDto(v.name))) } } }) { Text("이 버전 쓰기") }
                }
            }
            if (s.decisionModel != s.baseModel) Button({
                classes("link", "small")
                onClick { act { Http.post("/api/training/activate", ApiJson.encodeToString(ActivateDto.serializer(), ActivateDto(s.baseModel))) } }
            }) { Text("기본 모델(${s.baseModel})로 되돌리기") }
        }
    }
}

private fun trainingStatus(status: String) = when (status) {
    "RUNNING" -> "진행 중"
    "PASSED" -> "통과·적용"
    "REJECTED" -> "반영 안 함"
    "CANCELLED" -> "취소"
    else -> "실패"
}

/** 평가 게이트 결과 한 줄: 기존 → 새 모델 정답 수 */
private fun gateSummary(report: String?): String? = runCatching {
    val gate = ApiJson.parseToJsonElement(report ?: return null).jsonObject
    fun right(key: String) = gate[key]!!.jsonObject["right"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
    val base = right("base"); val cand = right("candidate")
    val total = gate["base"]!!.jsonObject["total"]!!.jsonPrimitive.content
    val replay = gate["replay"]!!.jsonObject
    listOf("agent" to "담당", "needsWeb" to "웹", "needsLocal" to "로컬").joinToString(" · ") { (k, label) -> "$label ${base[k]}→${cand[k]}/$total" } +
        " · 다른 판정 일치 ${replay["same"]!!.jsonPrimitive.content}/${replay["total"]!!.jsonPrimitive.content}"
}.getOrNull()

// JS 정규식(u 플래그)은 닫는 ]도 이스케이프해야 한다
private val citation = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)\\]")

/** 서버 설정(media.maxImages) 기본값과 같게 */
private const val MAX_IMAGES = 4

private fun citedNumbers(answer: String): Set<Int> =
    citation.findAll(answer).flatMap { m -> m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() } }.toSet()

/** 서버 시각(UTC ISO)을 브라우저 시간대로 */
private fun localTime(iso: String): String = runCatching {
    js("new Date(iso)").toLocaleString("ko-KR", js("({month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'})")) as String
}.getOrDefault(iso)

private fun gb(bytes: Long): String {
    val tenths = (bytes * 10 / (1024L * 1024 * 1024))
    return "${tenths / 10}.${tenths % 10}GB"
}

private fun agentName(agent: String) = when (agent) {
    "CHAT" -> "일반 대화"
    "DOCS" -> "문서 질의"
    "WEB" -> "웹 리서치"
    "CODE" -> "코드 도우미"
    "TASK" -> "작업 에이전트"
    "PERSONAL" -> "개인 비서"
    "IMAGE" -> "이미지 생성"
    else -> agent
}

private fun stageName(r: RoutingDto) = when (r.stage) {
    "EXPLICIT" -> "직접 지정"
    "RULE" -> "규칙"
    "DECISION_MODEL" -> "결정 모델 ${(r.confidence * 100).toInt()}%"
    "LLM" -> "메인 모델 판정"
    "DEFAULT" -> "기본값"
    else -> r.stage
} + (r.collections.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · @", separator = " @").orEmpty())

private fun riskName(risk: String) = when (risk) {
    "READ" -> "읽기"
    "NETWORK" -> "네트워크"
    "WRITE" -> "쓰기"
    "EXEC" -> "실행"
    else -> risk
}

private fun channelName(channel: String) = when (channel) {
    "CLI" -> "CLI"
    "WEB" -> "웹"
    "OPENAI_API" -> "API"
    else -> channel
}
