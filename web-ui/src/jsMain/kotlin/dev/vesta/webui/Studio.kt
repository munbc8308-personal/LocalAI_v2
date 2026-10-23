package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.vesta.api.AgentsDto
import dev.vesta.api.ApiJson
import dev.vesta.api.ChatRequestDto
import dev.vesta.api.CustomAgentDto
import dev.vesta.api.RunDetailDto
import dev.vesta.api.RunDto
import dev.vesta.api.RunEventDto
import dev.vesta.api.RunUpdateDto
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

/**
 * 에이전트 스튜디오 (PROJECT.md 7.16, M22): 에이전트 목록·만들기·고치기, 일 맡기기, 실행 목록과 단계 타임라인(실시간).
 * 실시간 소식은 `/api/runs/stream`(SSE, 내 실행만)
 */
@Composable
fun StudioView() {
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<AgentsDto?>(null) }
    val runs = remember { mutableStateListOf<RunDto>() }
    var selected by remember { mutableStateOf<Long?>(null) }
    var detail by remember { mutableStateOf<RunDetailDto?>(null) }
    val liveEvents = remember { mutableStateListOf<RunEventDto>() }
    var editing by remember { mutableStateOf<CustomAgentDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }

    fun loadAgents() = scope.launch {
        runCatching { Http.get("/api/agents", AgentsDto.serializer()) }.onSuccess { agents = it; error = null }.onFailure { error = it.message }
    }
    fun loadRuns() = scope.launch {
        runCatching { Http.get("/api/runs?limit=50", ListSerializer(RunDto.serializer())) }.onSuccess { runs.clear(); runs.addAll(it) }
    }
    fun open(id: Long) = scope.launch {
        selected = id
        liveEvents.clear()
        runCatching { Http.get("/api/runs/$id", RunDetailDto.serializer()) }.onSuccess { detail = it }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { loadAgents(); loadRuns() }
    // 실시간: 새 단계는 보고 있는 실행에 붙이고, 실행 상태는 목록에 반영
    DisposableEffect(Unit) {
        val source: dynamic = js("new EventSource('/api/runs/stream')")
        source.onmessage = { message: dynamic ->
            val text = message.data as String
            if (text != "{}") runCatching { ApiJson.decodeFromString(RunUpdateDto.serializer(), text) }.getOrNull()?.let { update ->
                update.event?.let { e ->
                    if (runs.none { it.id == e.run }) loadRuns()
                    if (e.run == selected && detail?.events?.none { it.seq == e.seq } != false && liveEvents.none { it.seq == e.seq }) liveEvents += e
                }
                update.run?.let { r ->
                    val i = runs.indexOfFirst { it.id == r.id }
                    if (i >= 0) runs[i] = r else runs.add(0, r)
                    if (r.id == selected) open(r.id)
                }
            }
        }
        onDispose { source.close() }
    }

    Div({ classes("page") }) {
        error?.let { P({ classes("error", "small") }) { Text(it) } }
        val a = agents
        // 일 맡기기: 사용자 정의 에이전트에게 (기본 에이전트는 대화 탭에서 /web·/code·/task)
        if (a != null) Div({ classes("card") }) {
            Div({ classes("row") }) { Span { B { Text("일 맡기기") } }; Span({ classes("muted", "small") }) { Text("CLI·메신저: /agent 이름 할 일") } }
            if (a.custom.isEmpty()) P({ classes("muted", "small") }) { Text("아래에서 에이전트를 먼저 만드세요.") }
            else {
                Select({ onChange { e -> target = e.value.orEmpty() } }) {
                    Option("", { if (target.isEmpty()) selected() }) { Text("에이전트 고르기") }
                    a.custom.forEach { c -> Option(c.name, { if (c.name == target) selected() }) { Text("${c.name} — ${c.description.take(40)}") } }
                }
                TextArea(task) { placeholder("할 일"); attr("rows", "3"); onInput { task = it.value } }
                Button({
                    classes("primary")
                    if (target.isEmpty() || task.isBlank()) disabled()
                    onClick {
                        val text = "/agent $target $task"
                        task = ""
                        // 새 대화로 보낸다. 진행은 아래 실행 목록·타임라인에서 실시간으로 본다
                        scope.launch { runCatching { Http.chat(ChatRequestDto(text)).collect { } }.onFailure { error = it.message } }
                    }
                }) { Text("맡기기") }
            }
        }
        // 실행 목록 + 타임라인
        Div({ classes("card") }) {
            Div({ classes("row") }) { Span { B { Text("실행") } }; Button({ classes("link", "small"); onClick { loadRuns() } }) { Text("새로 고침") } }
            if (runs.isEmpty()) P({ classes("muted", "small") }) { Text("아직 실행이 없습니다. 대화하거나 일을 맡기면 여기에 쌓입니다.") }
            runs.take(30).forEach { r ->
                Div({ classes("run"); if (r.id == selected) classes("selected"); onClick { open(r.id) } }) {
                    Div({ classes("row", "small") }) {
                        Span { B { Text(r.customAgent ?: agentLabel(r.agent)) }; Text(" · ${r.input.take(60)}") }
                        Span({ classes(statusClass(r.status)) }) { Text(statusLabel(r.status)) }
                    }
                }
            }
        }
        detail?.let { d ->
            Div({ classes("card") }) {
                Div({ classes("row") }) {
                    Span { B { Text("타임라인 #${d.run.id}") } }
                    Span({ classes(statusClass(d.run.status), "small") }) { Text(statusLabel(d.run.status)) }
                }
                (d.events + liveEvents).distinctBy { it.seq }.sortedBy { it.seq }.forEach { e -> EventRow(e) }
            }
        }
        // 에이전트 목록·만들기
        if (a != null) Div({ classes("card") }) {
            Div({ classes("row") }) {
                Span { B { Text("에이전트") } }
                Button({ classes("link", "small"); onClick { editing = CustomAgentDto("", "", "") } }) { Text("새 에이전트") }
            }
            a.custom.forEach { c ->
                Div({ classes("run") }) {
                    Div({ classes("row", "small") }) {
                        Span { B { Text(c.name) }; Text(" · ${c.description}") }
                        Span {
                            Button({ classes("link", "small"); onClick { editing = c } }) { Text("고치기") }
                            Button({
                                classes("link", "small", "bad")
                                onClick {
                                    if (!window.confirm("${c.name} 에이전트를 지울까요?")) return@onClick
                                    scope.launch { runCatching { Http.delete("/api/agents/${c.name}") }.onFailure { error = it.message }; loadAgents() }
                                }
                            }) { Text("지우기") }
                        }
                    }
                    Div({ classes("muted", "small") }) { Text("모델 ${if (c.model == "CODE") "코드" else "일반"} · 도구 ${c.tools.ifEmpty { listOf("없음") }.joinToString()}") }
                }
            }
            a.builtin.forEach { b -> Div({ classes("row", "small") }) { Span({ classes("muted") }) { Text("기본 · ${b.label}") }; Span({ classes("muted") }) { Text(b.description) } } }
        }
        editing?.let { e -> AgentEditor(e, a?.tools.orEmpty(), onCancel = { editing = null }) { saved ->
            scope.launch {
                runCatching { Http.put("/api/agents/${saved.name}", ApiJson.encodeToString(CustomAgentDto.serializer(), saved)) }
                    .onSuccess { editing = null; error = null; loadAgents() }
                    .onFailure { error = it.message }
            }
        } }
    }
}

@Composable
private fun AgentEditor(initial: CustomAgentDto, tools: List<dev.vesta.api.ToolInfoDto>, onCancel: () -> Unit, onSave: (CustomAgentDto) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var instructions by remember(initial) { mutableStateOf(initial.instructions) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var chosen by remember(initial) { mutableStateOf(initial.tools.toSet()) }
    Div({ classes("card", "stack") }) {
        Div({ classes("row") }) { Span { B { Text(if (initial.name.isEmpty()) "새 에이전트" else "${initial.name} 고치기") } }; Button({ classes("link", "small"); onClick { onCancel() } }) { Text("닫기") } }
        Input(InputType.Text) { value(name); placeholder("이름 (공백 없이, /agent 이름)"); if (initial.name.isNotEmpty()) disabled(); onInput { name = it.value } }
        Input(InputType.Text) { value(description); placeholder("역할 (예: 코드 리뷰어 — 변경 사항을 읽고 문제를 찾는다)"); onInput { description = it.value } }
        TextArea(instructions) { placeholder("지침: 어떻게 일할지, 무엇을 하지 말지, 답 형식"); attr("rows", "6"); onInput { instructions = it.value } }
        Select({ onChange { e -> model = e.value ?: "GENERAL" } }) {
            Option("GENERAL", { if (model == "GENERAL") selected() }) { Text("일반 모델") }
            Option("CODE", { if (model == "CODE") selected() }) { Text("코드 모델") }
        }
        Div({ classes("small") }) { B { Text("도구") }; Span({ classes("muted") }) { Text(" (내가 쓸 수 있는 것만 · 쓰기·셸은 실행 때 승인)") } }
        tools.forEach { t ->
            Label(attrs = { classes("row", "small") }) {
                Span {
                    Input(InputType.Checkbox) {
                        checked(t.name in chosen)
                        onChange { chosen = if (t.name in chosen) chosen - t.name else chosen + t.name }
                    }
                    Text(" ${t.name}")
                }
                Span({ classes("muted") }) { Text(riskLabel(t.risk)) }
            }
        }
        Button({ classes("primary"); onClick { onSave(CustomAgentDto(name.trim(), description.trim(), instructions, model, chosen.toList())) } }) { Text("저장") }
    }
}

@Composable
private fun EventRow(e: RunEventDto) {
    var open by remember(e.run, e.seq) { mutableStateOf(e.kind == "answer" || e.kind == "approval" || e.kind == "error") }
    Div({ classes("tool") }) {
        Div({ classes("row", "small"); onClick { open = !open } }) {
            Span { Text("${eventIcon(e.kind)} ${e.title}") }
            Span({ classes("muted") }) { Text(e.at.substringAfter('T').take(8)) }
        }
        if (open) e.detail?.takeIf { it.isNotBlank() }?.let { Pre({ classes("small") }) { Text(it) } }
    }
}

private fun eventIcon(kind: String) = when (kind) {
    "input" -> "💬"
    "routed" -> "🧭"
    "status" -> "·"
    "tool_started" -> "🔧"
    "tool_finished" -> "✓"
    "approval" -> "✋"
    "sources" -> "📚"
    "grounding" -> "🔎"
    "answer" -> "🗨"
    "done" -> "■"
    "error" -> "⚠"
    "media" -> "🖼"
    else -> "·"
}

private fun agentLabel(agent: String) = when (agent) {
    "CHAT" -> "대화"
    "DOCS" -> "문서"
    "WEB" -> "웹 리서치"
    "CODE" -> "코드 도우미"
    "TASK" -> "작업"
    "PERSONAL" -> "개인 비서"
    "IMAGE" -> "이미지"
    else -> agent
}

private fun statusLabel(status: String) = when (status) {
    "RUNNING" -> "진행 중"
    "DONE" -> "끝"
    "FAILED" -> "실패"
    else -> "멈춤"
}

private fun statusClass(status: String) = when (status) {
    "RUNNING" -> "muted"
    "DONE" -> "good"
    else -> "bad"
}

private fun riskLabel(risk: String) = when (risk) {
    "READ" -> "읽기"
    "NETWORK" -> "인터넷"
    "WRITE" -> "쓰기 (승인)"
    "EXEC" -> "셸 (승인)"
    else -> risk
}
