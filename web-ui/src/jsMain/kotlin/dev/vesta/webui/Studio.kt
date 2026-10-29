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
import dev.vesta.api.A2aAddDto
import dev.vesta.api.A2aAgentDto
import dev.vesta.api.A2aPreviewDto
import dev.vesta.api.AgentsDto
import dev.vesta.api.ApiJson
import dev.vesta.api.ChatRequestDto
import dev.vesta.api.CustomAgentDto
import dev.vesta.api.RunDetailDto
import dev.vesta.api.RunDto
import dev.vesta.api.RunEventDto
import dev.vesta.api.RunUpdateDto
import dev.vesta.api.SkillDto
import dev.vesta.api.TeamDto
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
    var editingTeam by remember { mutableStateOf<TeamDto?>(null) }
    var addingA2a by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<SkillDto?>(null) }
    // 스킬 제안 켜기·끄기 (M28): 내 설정에 함께 둔다
    var settings by remember { mutableStateOf<dev.vesta.api.SettingsDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }

    fun loadAgents() = scope.launch {
        runCatching { Http.get("/api/agents", AgentsDto.serializer()) }.onSuccess { agents = it; error = null }.onFailure { error = it.message }
        runCatching { Http.get("/api/me/settings", dev.vesta.api.SettingsDto.serializer()) }.onSuccess { settings = it }
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
        // 일 맡기기: 사용자 정의 에이전트·팀에게 (기본 에이전트는 대화 탭에서 /web·/code·/task). target은 "agent 이름" 또는 "team 이름"
        if (a != null) Div({ classes("card") }) {
            Div({ classes("row") }) { Span { B { Text("일 맡기기") } }; Span({ classes("muted", "small") }) { Text("CLI·메신저: /agent 이름 할 일, /team 이름 할 일, /스킬 할 일") } }
            if (a.custom.isEmpty() && a.a2a.isEmpty() && a.skills.isEmpty()) P({ classes("muted", "small") }) { Text("아래에서 에이전트를 먼저 만드세요.") }
            else {
                Select({ onChange { e -> target = e.value.orEmpty() } }) {
                    Option("", { if (target.isEmpty()) selected() }) { Text("에이전트·팀 고르기") }
                    a.custom.forEach { c -> Option("agent ${c.name}", { if ("agent ${c.name}" == target) selected() }) { Text("${c.name} — ${c.description.take(40)}") } }
                    a.a2a.forEach { r -> Option("agent ${r.name}", { if ("agent ${r.name}" == target) selected() }) { Text("${r.name} (A2A) — ${r.description.take(40)}") } }
                    a.skills.forEach { k -> Option(k.name, { if (k.name == target) selected() }) { Text("스킬 /${k.name} — ${k.description.take(40)}") } }
                    a.teams.forEach { t -> Option("team ${t.name}", { if ("team ${t.name}" == target) selected() }) { Text("팀 ${t.name} — ${t.description.take(40)}") } }
                }
                TextArea(task) { placeholder("할 일"); attr("rows", "3"); onInput { task = it.value } }
                Button({
                    classes("primary")
                    if (target.isEmpty() || task.isBlank()) disabled()
                    onClick {
                        val text = "/$target $task"
                        task = ""
                        // 새 대화로 보낸다. 진행은 아래 실행 목록·타임라인에서 실시간으로 본다
                        scope.launch { runCatching { Http.chat(ChatRequestDto(text)).collect { } }.onFailure { error = it.message } }
                    }
                }) { Text("맡기기") }
            }
        }
        // 실행 목록 + 타임라인
        Div({ classes("card") }) {
            Div({ classes("row") }) {
                Span { B { Text("실행") } }
                Span {
                    Button({ classes("link", "small"); onClick { loadRuns() } }) { Text("새로 고침") }
                    if (runs.any { it.status != "RUNNING" }) Button({
                        classes("link", "small", "danger")
                        onClick {
                            if (kotlinx.browser.window.confirm("끝난 실행 기록을 모두 지울까요? 대화는 남습니다. 되돌릴 수 없습니다.")) scope.launch {
                                runCatching { Http.delete("/api/runs") }
                                    .onSuccess { if (detail?.run?.status != "RUNNING") { selected = null; detail = null }; loadRuns() }
                                    .onFailure { error = it.message }
                            }
                        }
                    }) { Text("모두 지우기") }
                }
            }
            if (runs.isEmpty()) P({ classes("muted", "small") }) { Text("아직 실행이 없습니다. 대화하거나 일을 맡기면 여기에 쌓입니다.") }
            runs.take(30).forEach { r ->
                Div({ classes("run"); if (r.id == selected) classes("selected"); onClick { open(r.id) } }) {
                    Div({ classes("row", "small") }) {
                        Span { B { Text(if (r.agent == "TEAM") "팀 ${r.customAgent}" else r.customAgent ?: agentLabel(r.agent)) }; Text(" · ${r.input.take(60)}") }
                        Span {
                            Span({ classes(statusClass(r.status)) }) { Text(statusLabel(r.status)) }
                            if (r.status != "RUNNING") Button({
                                classes("link", "small", "remove")
                                attr("aria-label", "실행 기록 지우기")
                                onClick { event ->
                                    event.stopPropagation()
                                    scope.launch {
                                        runCatching { Http.delete("/api/runs/${r.id}") }
                                            .onSuccess { runs.removeAll { it.id == r.id }; if (selected == r.id) { selected = null; detail = null } }
                                            .onFailure { error = it.message }
                                    }
                                }
                            }) { Text("🗑") }
                        }
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
                // 나눠 맡긴 사이의 단계(멤버가 한 일)는 들여 쓴다
                var depth = 0
                (d.events + liveEvents).distinctBy { it.seq }.sortedBy { it.seq }.forEach { e ->
                    if (e.kind == "delegate_finished") depth = maxOf(0, depth - 1)
                    EventRow(e, depth)
                    if (e.kind == "delegate_started") depth++
                }
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
        // 팀: 리더가 delegate 도구로 멤버에게 맡긴다
        if (a != null) Div({ classes("card") }) {
            Div({ classes("row") }) {
                Span { B { Text("팀") } }
                Button({
                    classes("link", "small")
                    if (a.custom.isEmpty() || a.custom.size + a.a2a.size < 2) disabled()
                    onClick { editingTeam = TeamDto("", "", "", emptyList()) }
                }) { Text("새 팀") }
            }
            if (a.teams.isEmpty()) P({ classes("muted", "small") }) { Text("에이전트를 둘 이상 만든 뒤 리더와 멤버를 골라 팀을 만듭니다. 리더가 일을 나눠 멤버에게 맡깁니다.") }
            a.teams.forEach { t ->
                Div({ classes("run") }) {
                    Div({ classes("row", "small") }) {
                        Span { B { Text(t.name) }; Text(" · ${t.description}") }
                        Span {
                            Button({ classes("link", "small"); onClick { editingTeam = t } }) { Text("고치기") }
                            Button({
                                classes("link", "small", "bad")
                                onClick {
                                    if (!window.confirm("${t.name} 팀을 지울까요? (에이전트는 남습니다)")) return@onClick
                                    scope.launch { runCatching { Http.delete("/api/teams/${t.name}") }.onFailure { error = it.message }; loadAgents() }
                                }
                            }) { Text("지우기") }
                        }
                    }
                    val names = (a.custom.map { it.name } + a.a2a.map { it.name }).toSet()
                    val missing = (listOf(t.leader) + t.members).filter { it !in names }
                    Div({ classes("muted", "small") }) { Text("리더 ${t.leader} · 멤버 ${t.members.joinToString()}") }
                    if (missing.isNotEmpty()) Div({ classes("bad", "small") }) { Text("지워진 에이전트: ${missing.joinToString()}") }
                }
            }
        }
        // 스킬: 자주 하는 일의 지침. /이름 할 일 (웹에서 만든 것만 여기서 고친다. 파일 스킬은 파일로)
        if (a != null) Div({ classes("card") }) {
            Div({ classes("row") }) {
                Span { B { Text("스킬") } }
                Button({ classes("link", "small"); onClick { editingSkill = SkillDto("", "", "") } }) { Text("새 스킬") }
            }
            if (a.skills.isEmpty()) P({ classes("muted", "small") }) { Text("자주 하는 일을 지침으로 만들어 /이름 할 일 로 부릅니다. ~/.vesta/skills/<이름>/SKILL.md 파일이나 작업 폴더의 .vesta/skills 도 읽습니다.") }
            a.skills.forEach { k ->
                Div({ classes("run") }) {
                    Div({ classes("row", "small") }) {
                        Span { B { Text("/${k.name}") }; Text(" · ${k.description}") }
                        if (k.source == "WEB") Span {
                            Button({ classes("link", "small"); onClick { editingSkill = k } }) { Text("고치기") }
                            Button({
                                classes("link", "small", "bad")
                                onClick {
                                    if (!window.confirm("/${k.name} 스킬을 지울까요?")) return@onClick
                                    scope.launch { runCatching { Http.delete("/api/skills/${k.name}") }.onFailure { error = it.message }; loadAgents() }
                                }
                            }) { Text("지우기") }
                        } else Span({ classes("muted") }) { Text(when (k.source) { "PERSONAL" -> "내 파일"; "BUILTIN" -> "기본"; else -> "작업 폴더" }) }
                    }
                    Div({ classes("muted", "small") }) { Text(agentLabel(k.agent) + (if (k.tools.isNotEmpty()) " · 도구 ${k.tools.joinToString()}" else "") + if (k.auto) " · 실행에서 만듦" else "") }
                }
            }
            // 제안된 스킬 (M28 SK-11): 도구로 끝낸 실행에서 만든 초안. 쓰기(저장)해야 /이름 으로 부를 수 있다
            if (a.drafts.isNotEmpty()) {
                Div({ classes("small") }) { B { Text("제안된 스킬 ${a.drafts.size}개") }; Span({ classes("muted") }) { Text(" · 승인하기 전에는 부를 수 없습니다") } }
                a.drafts.forEach { k ->
                    Div({ classes("run") }) {
                        Div({ classes("row", "small") }) {
                            Span { B { Text("/${k.name}") }; Text(" · ${k.description}") }
                            Span {
                                Button({
                                    classes("link", "small")
                                    onClick {
                                        if (!window.confirm("/${k.name} 을 쓸까요? 아래 지침대로, 도구 ${k.tools.joinToString().ifEmpty { "없음" }}로 일합니다.")) return@onClick
                                        scope.launch {
                                            runCatching { Http.put("/api/skills/${k.name}", ApiJson.encodeToString(SkillDto.serializer(), k)) }
                                                .onSuccess { error = null }.onFailure { error = it.message }
                                            loadAgents()
                                        }
                                    }
                                }) { Text("쓰기") }
                                Button({ classes("link", "small"); onClick { editingSkill = k } }) { Text("고쳐서 쓰기") }
                                k.run?.let { id -> Button({ classes("link", "small"); onClick { open(id) } }) { Text("만든 실행") } }
                                Button({
                                    classes("link", "small", "bad")
                                    onClick { scope.launch { runCatching { Http.delete("/api/skills/${k.name}") }.onFailure { error = it.message }; loadAgents() } }
                                }) { Text("버리기") }
                            }
                        }
                        Div({ classes("muted", "small") }) { Text(if (k.tools.isNotEmpty()) "도구 ${k.tools.joinToString()}" else "도구 없음") }
                        Pre({ classes("small") }) { Text(k.body) }
                    }
                }
            }
            settings?.let { s ->
                Div({ classes("row", "small") }) {
                    Span { Text("스킬 제안") }
                    Select({
                        onChange { e ->
                            val next = s.copy(skillAuto = e.value == "on", voices = emptyList())
                            settings = next
                            scope.launch { runCatching { Http.put("/api/me/settings", ApiJson.encodeToString(dev.vesta.api.SettingsDto.serializer(), next)) }.onFailure { error = it.message ?: "저장 실패" } }
                        }
                    }) {
                        val on = s.skillAuto != false
                        Option("on", { if (on) selected() }) { Text("켬: 도구로 여러 번 일한 실행을 쉬는 때 초안으로") }
                        Option("off", { if (!on) selected() }) { Text("끔: /skillify 로 직접 만들 때만") }
                    }
                }
            }
        }
        editingSkill?.let { k -> SkillEditor(k, a?.tools.orEmpty(), onCancel = { editingSkill = null }) { saved ->
            scope.launch {
                runCatching { Http.put("/api/skills/${saved.name}", ApiJson.encodeToString(SkillDto.serializer(), saved)) }
                    .onSuccess { editingSkill = null; error = null; loadAgents() }
                    .onFailure { error = it.message }
            }
        } }
        // A2A 에이전트: 바깥 에이전트를 표준 프로토콜로 (Mac 주인만)
        if (a != null && a.canA2a) Div({ classes("card") }) {
            Div({ classes("row") }) {
                Span { B { Text("A2A 에이전트") } }
                Button({ classes("link", "small"); onClick { addingA2a = !addingA2a } }) { Text(if (addingA2a) "닫기" else "붙이기") }
            }
            if (a.a2a.isEmpty() && !addingA2a) P({ classes("muted", "small") }) { Text("다른 곳에서 돌아가는 에이전트를 주소(Agent Card)로 붙여 /agent 이름 이나 팀 멤버로 씁니다.") }
            a.a2a.forEach { r -> A2aRow(r) {
                if (!window.confirm("${r.name} A2A 에이전트를 지울까요?")) return@A2aRow
                scope.launch { runCatching { Http.delete("/api/a2a/${r.name}") }.onFailure { error = it.message }; loadAgents() }
            } }
            if (addingA2a) A2aAdder(onError = { error = it }) { addingA2a = false; error = null; loadAgents() }
        }
        editingTeam?.let { t -> TeamEditor(t, a?.custom.orEmpty(), a?.a2a.orEmpty(), onCancel = { editingTeam = null }) { saved ->
            scope.launch {
                runCatching { Http.put("/api/teams/${saved.name}", ApiJson.encodeToString(TeamDto.serializer(), saved)) }
                    .onSuccess { editingTeam = null; error = null; loadAgents() }
                    .onFailure { error = it.message }
            }
        } }
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
private fun SkillEditor(initial: SkillDto, tools: List<dev.vesta.api.ToolInfoDto>, onCancel: () -> Unit, onSave: (SkillDto) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var body by remember(initial) { mutableStateOf(initial.body) }
    var agent by remember(initial) { mutableStateOf(initial.agent) }
    var chosen by remember(initial) { mutableStateOf(initial.tools.toSet()) }
    Div({ classes("card", "stack") }) {
        Div({ classes("row") }) { Span { B { Text(if (initial.name.isEmpty()) "새 스킬" else "/${initial.name} 고치기") } }; Button({ classes("link", "small"); onClick { onCancel() } }) { Text("닫기") } }
        Input(InputType.Text) { value(name); placeholder("이름 (공백 없이, /이름 으로 부름)"); if (initial.name.isNotEmpty()) disabled(); onInput { name = it.value } }
        Input(InputType.Text) { value(description); placeholder("무엇을 하는 스킬인지 (예: 변경 사항을 리뷰한다)"); onInput { description = it.value } }
        Select({ onChange { e -> agent = e.value ?: "CHAT" } }) {
            listOf("CHAT", "TASK", "CODE", "WEB", "DOCS").forEach { v -> Option(v, { if (v == agent) selected() }) { Text(agentLabel(v)) } }
        }
        TextArea(body) { placeholder("지침. \$ARGUMENTS 자리에 할 일이 들어갑니다 (없으면 끝에 붙음). 예: 다음 글을 세 줄로 요약하고 핵심 단어를 굵게: \$ARGUMENTS"); attr("rows", "8"); onInput { body = it.value } }
        if (agent == "TASK") {
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
        }
        Button({ classes("primary"); onClick { onSave(SkillDto(name.trim(), description.trim(), body, agent, if (agent == "TASK") chosen.toList() else emptyList(), auto = initial.auto, run = initial.run)) } }) { Text("저장") }
    }
}

@Composable
private fun A2aRow(r: A2aAgentDto, onDelete: () -> Unit) {
    Div({ classes("run") }) {
        Div({ classes("row", "small") }) {
            Span { B { Text(r.name) }; Text(" · ${r.displayName} — ${r.description.take(80)}") }
            Button({ classes("link", "small", "bad"); onClick { onDelete() } }) { Text("지우기") }
        }
        val host = r.endpoint.substringAfter("://").substringBefore('/')
        Div({ classes("muted", "small") }) {
            Text("$host · A2A ${r.protocol}" + (if (r.local) " · 내부" else "") + (if (r.streaming) " · 실시간" else "") + if (r.auth) (if (r.hasToken) " · 토큰 있음" else "") else "")
        }
        if (r.auth && !r.hasToken) Div({ classes("bad", "small") }) {
            Text("인증이 필요합니다. 터미널에서: security add-generic-password -U -s dev.vesta.a2a -a ${r.name} -w")
        }
        if (r.skills.isNotEmpty()) Div({ classes("muted", "small") }) { Text("할 수 있는 일: " + r.skills.take(5).joinToString("; ")) }
    }
}

/** 주소 → 카드 읽기(미리 보기·경고) → 이름 정해 등록 */
@Composable
private fun A2aAdder(onError: (String?) -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<A2aPreviewDto?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(false) }
    Div({ classes("stack") }) {
        Input(InputType.Url) { value(url); placeholder("에이전트 주소 (예: https://agent.example.com 또는 …/agent-card.json)"); onInput { url = it.value; preview = null } }
        Label(attrs = { classes("small") }) {
            Input(InputType.Checkbox) { checked(local); onChange { local = !local; preview = null } }
            Text(" 내부 에이전트 (이 Mac·내 네트워크·Tailscale에서 내가 띄운 것)")
        }
        Button({
            if (url.isBlank() || busy) disabled()
            onClick {
                busy = true
                scope.launch {
                    runCatching { Http.post("/api/a2a/preview", ApiJson.encodeToString(A2aAddDto.serializer(), A2aAddDto(url.trim(), local = local)), A2aPreviewDto.serializer()) }
                        .onSuccess { preview = it; name = it.suggestedName; onError(null) }
                        .onFailure { onError(it.message) }
                    busy = false
                }
            }
        }) { Text(if (busy) "읽는 중…" else "카드 읽기") }
        preview?.let { p ->
            Div({ classes("small") }) { B { Text(p.displayName) }; Text(" — ${p.description}") }
            Div({ classes("muted", "small") }) { Text("연결: ${p.endpoint} · A2A ${p.protocol}" + (if (p.streaming) " · 실시간" else "") + (p.version?.let { " · 버전 $it" } ?: "")) }
            if (p.skills.isNotEmpty()) Div({ classes("muted", "small") }) { Text("할 수 있는 일: " + p.skills.joinToString("; ")) }
            p.warnings.forEach { w -> Div({ classes("bad", "small") }) { Text("⚠ $w") } }
            Input(InputType.Text) { value(name); placeholder("부를 이름 (/agent 이름)"); onInput { name = it.value } }
            Button({
                classes("primary")
                if (name.isBlank() || busy) disabled()
                onClick {
                    busy = true
                    scope.launch {
                        runCatching { Http.put("/api/a2a/${name.trim()}", ApiJson.encodeToString(A2aAddDto.serializer(), A2aAddDto(url.trim(), p.endpoint, local))) }
                            .onSuccess { onSaved() }
                            .onFailure { onError(it.message) }
                        busy = false
                    }
                }
            }) { Text("경고를 확인했고 붙입니다") }
        }
    }
}

@Composable
private fun TeamEditor(initial: TeamDto, agents: List<CustomAgentDto>, remote: List<A2aAgentDto>, onCancel: () -> Unit, onSave: (TeamDto) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var theme by remember(initial) { mutableStateOf(initial.theme.orEmpty()) }
    var leader by remember(initial) { mutableStateOf(initial.leader) }
    var members by remember(initial) { mutableStateOf(initial.members.toSet()) }
    Div({ classes("card", "stack") }) {
        Div({ classes("row") }) { Span { B { Text(if (initial.name.isEmpty()) "새 팀" else "${initial.name} 고치기") } }; Button({ classes("link", "small"); onClick { onCancel() } }) { Text("닫기") } }
        Input(InputType.Text) { value(name); placeholder("팀 이름 (공백 없이, /team 이름)"); if (initial.name.isNotEmpty()) disabled(); onInput { name = it.value } }
        Input(InputType.Text) { value(description); placeholder("무엇을 하는 팀인지 (예: 기획 → 개발 → 리뷰)"); onInput { description = it.value } }
        Div({ classes("small") }) { B { Text("리더") }; Span({ classes("muted") }) { Text(" (일을 나누고 결과를 모아 답한다)") } }
        Select({ onChange { e -> leader = e.value.orEmpty(); members = members - leader } }) {
            Option("", { if (leader.isEmpty()) selected() }) { Text("리더 고르기") }
            agents.forEach { a -> Option(a.name, { if (a.name == leader) selected() }) { Text("${a.name} — ${a.description.take(40)}") } }
        }
        Div({ classes("small") }) { B { Text("멤버") }; Span({ classes("muted") }) { Text(" (리더가 맡기는 에이전트, 최대 6)") } }
        val candidates = agents.filter { it.name != leader }.map { it.name to it.description } + remote.map { it.name to "(A2A) ${it.description}" }
        candidates.forEach { (memberName, memberDescription) ->
            Label(attrs = { classes("row", "small") }) {
                Span {
                    Input(InputType.Checkbox) {
                        checked(memberName in members)
                        onChange { members = if (memberName in members) members - memberName else members + memberName }
                    }
                    Text(" $memberName")
                }
                Span({ classes("muted") }) { Text(memberDescription.take(40)) }
            }
        }
        Div({ classes("row", "small") }) {
            Span { Text("사무실 방 꾸미기") }
            Select({ onChange { e -> theme = e.value.orEmpty() } }) {
                listOf("" to "자동", "office" to "사무실", "cafe" to "카페", "lab" to "연구소", "library" to "도서관", "studio" to "스튜디오", "factory" to "공장").forEach { (v, l) -> Option(v, { if (v == theme) selected() }) { Text(l) } }
            }
        }
        Button({
            classes("primary")
            if (leader.isEmpty() || members.isEmpty()) disabled()
            onClick { onSave(TeamDto(name.trim(), description.trim(), leader, candidates.map { it.first }.filter { it in members }, theme = theme.ifEmpty { null })) }
        }) { Text("저장") }
    }
}

@Composable
private fun EventRow(e: RunEventDto, depth: Int = 0) {
    var open by remember(e.run, e.seq) { mutableStateOf(e.kind == "answer" || e.kind == "approval" || e.kind == "error") }
    Div({ classes("tool"); if (depth > 0) style { property("margin-left", "${depth * 18}px"); property("border-left", "2px solid var(--line)"); property("padding-left", "8px") } }) {
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
    "delegate_started" -> "👉"
    "delegate_finished" -> "📥"
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
    "TEAM" -> "팀"
    "CURATOR" -> "학습 재료 큐레이터"
    "PERSONAL" -> "개인 비서"
    "IMAGE" -> "이미지"
    "VIDEO" -> "영상"
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
