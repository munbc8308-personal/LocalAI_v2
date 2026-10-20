package dev.vesta.webui

import androidx.compose.runtime.Composable
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
import dev.vesta.api.MeDto
import dev.vesta.api.MediaItemDto
import dev.vesta.api.RoutingDto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoFocus
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
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
                    runCatching { Http.post("/api/login", ApiJson.encodeToString(LoginDto.serializer(), LoginDto(password)), MeDto.serializer()) }
                        .onSuccess(onLogin)
                        .onFailure { error = it.message; password = "" }
                    sending = false
                }
            }
        }) {
            H2 { Text("Vesta") }
            P({ classes("muted") }) { Text("이 Mac의 로컬 AI 어시스턴트") }
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
        if (turn.answer.isNotBlank() && !(chat.busy && turn === chat.turns.lastOrNull())) SpeakButton(turn)
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
        P({ classes("muted", "small") }) { Text("Vesta ${d.version} · ${me.name} (${me.role})") }
    }
}

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
