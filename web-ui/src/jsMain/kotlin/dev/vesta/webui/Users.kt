package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.vesta.api.ApiJson
import dev.vesta.api.ChangePasswordDto
import dev.vesta.api.ConsentDto
import dev.vesta.api.MeDto
import dev.vesta.api.MessengersDto
import dev.vesta.api.PairCodeDto
import dev.vesta.api.NewUserDto
import dev.vesta.api.PasswordDto
import dev.vesta.api.RoleDto
import dev.vesta.api.UserDto
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** 사용자 관리 (M15, U-01·U-05): 목록·오늘 사용량, 추가, 역할, 비밀번호 재설정, 학습 동의, 삭제. Mac 주인만 */
@Composable
fun UsersCard(me: MeDto) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("MEMBER") }
    var newPassword by remember { mutableStateOf("") }
    var messengers by remember { mutableStateOf<MessengersDto?>(null) }
    /** 방금 추가한 사용자에게 전해 줄 안내 (이름·비밀번호·주소·메신저 코드, M20) */
    var invite by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var codes by remember { mutableStateOf<Map<String, PairCodeDto>>(emptyMap()) }
    fun load() = scope.launch {
        runCatching { Http.get("/api/users", ListSerializer(UserDto.serializer())) }
            .onSuccess { users = it; error = null }
            .onFailure { error = it.message }
        runCatching { Http.get("/api/messengers", MessengersDto.serializer()) }.onSuccess { messengers = it }
    }
    /** [name]의 [channel] 연결 코드 (주인이 대신 만든다) */
    fun pairFor(name: String, channel: String) = scope.launch {
        runCatching { Http.post("/api/messengers/$channel/pair/" + js("encodeURIComponent")(name) as String, "{}", PairCodeDto.serializer()) }
            .onSuccess { codes = codes + ("$name/$channel" to it); error = null }
            .onFailure { error = it.message }
    }
    fun act(done: String? = null, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onSuccess { notice = done; error = null }.onFailure { error = it.message; notice = null }
        load()
    }
    LaunchedEffect(Unit) { load() }
    Div({ classes("card") }) {
        Div({ classes("row") }) { Span { B { Text("사용자") } }; Span({ classes("muted", "small") }) { Text("오늘 질문 · 토큰") } }
        error?.let { P({ classes("error", "small") }) { Text(it) } }
        notice?.let { P({ classes("good", "small") }) { Text(it) } }
        users.orEmpty().forEach { u ->
            val path = "/api/users/" + js("encodeURIComponent")(u.name) as String
            Div({ classes("run") }) {
                Div({ classes("row", "small") }) {
                    Span {
                        B { Text(u.name) }
                        Text(" · ${roleName(u.role)}" + (if (!u.hasPassword) " · 비밀번호 없음" else ""))
                    }
                    Span { Text("${u.questionsToday}개 · ${u.tokensToday}") }
                }
                // 메신저 연결 (M20): 연결된 계정·끊기, 연결 코드 만들기
                messengers?.let { m ->
                    m.links.filter { it.user == u.name }.forEach { link ->
                        Div({ classes("row", "small") }) {
                            Span({ classes("muted") }) { Text("${messengerName(link.channel)} 연결됨 · ${link.externalId}") }
                            Button({
                                classes("link", "small")
                                onClick { if (window.confirm("${u.name} 의 ${messengerName(link.channel)} 연결을 끊을까요?")) act("연결을 끊었습니다") { Http.delete("/api/messengers/${link.channel}/${link.externalId}") } }
                            }) { Text("끊기") }
                        }
                    }
                    if (m.enabled.isNotEmpty()) Div({ classes("row", "small") }) {
                        Span({ classes("muted") }) { Text("메신저 연결 코드") }
                        Span { m.enabled.forEach { ch -> Button({ classes("link", "small"); onClick { pairFor(u.name, ch) } }) { Text(messengerName(ch)) } } }
                    }
                    m.enabled.mapNotNull { codes["${u.name}/$it"] }.forEach { CodeView(it) }
                }
                if (u.role != "OWNER") Div({ classes("row", "small") }) {
                    Select({
                        onChange { e -> e.value?.let { role -> act("${u.name}: ${roleName(role)}") { Http.post("$path/role", ApiJson.encodeToString(RoleDto.serializer(), RoleDto(role))) } } }
                    }) {
                        listOf("MEMBER", "GUEST").forEach { r -> Option(r, { if (r == u.role) selected() }) { Text(roleName(r)) } }
                    }
                    Span {
                        Button({
                            classes("link", "small")
                            onClick {
                                val password = window.prompt("${u.name} 의 새 비밀번호 (8자 이상)") ?: return@onClick
                                act("${u.name} 의 비밀번호를 바꿨습니다") { Http.post("$path/password", ApiJson.encodeToString(PasswordDto.serializer(), PasswordDto(password))) }
                            }
                        }) { Text("비밀번호") }
                        Button({
                            classes("link", "small")
                            onClick { act { Http.post("$path/consent", ApiJson.encodeToString(ConsentDto.serializer(), ConsentDto(!u.trainingConsent))) } }
                        }) { Text(if (u.trainingConsent) "학습 동의 ✓" else "학습 동의 안 함") }
                        Button({
                            classes("link", "small", "bad")
                            onClick {
                                if (window.prompt("${u.name} 의 대화·컬렉션·이미지가 모두 지워집니다. 지우려면 이름을 입력하세요") != u.name) return@onClick
                                act("${u.name} 을(를) 지웠습니다") { Http.delete(path) }
                            }
                        }) { Text("삭제") }
                    }
                }
            }
        }
        // 초대 안내 (M20): 새 사용자에게 그대로 전해 줄 내용
        invite?.let { (name, password) ->
            Div({ classes("run") }) {
                Div({ classes("row", "small") }) {
                    Span { B { Text("$name 에게 전해 줄 안내") } }
                    Button({ classes("link", "small"); onClick { invite = null } }) { Text("닫기") }
                }
                Div({ classes("small") }) { Text("웹: ${window.location.origin} · 이름 $name · 비밀번호 ${password ?: "(없음: 비밀번호 단추로 정하기)"}") }
                Div({ classes("small", "muted") }) { Text("휴대폰은 Tailscale에 같은 계정으로 로그인한 뒤 위 주소로. 메신저로 쓰려면 아래 코드를 만들어 봇에게 보내게 하세요 (10분)") }
                messengers?.enabled?.let { enabled ->
                    Div({ classes("row", "small") }) { Span {}; Span { enabled.forEach { ch -> Button({ classes("link", "small"); onClick { pairFor(name, ch) } }) { Text("${messengerName(ch)} 코드") } } } }
                    enabled.mapNotNull { codes["$name/$it"] }.forEach { CodeView(it) }
                }
            }
        }
        Form(attrs = {
            classes("stack")
            addEventListener("submit") { event ->
                event.preventDefault()
                if (newName.isBlank()) return@addEventListener
                val name = newName.trim()
                val password = newPassword.ifEmpty { null }
                act("$name 을(를) 추가했습니다. 아래 안내를 전해 주세요") {
                    Http.post("/api/users", ApiJson.encodeToString(NewUserDto.serializer(), NewUserDto(name, newRole, password)))
                    invite = name to password
                    newName = ""
                    newPassword = ""
                }
            }
        }) {
            Div({ classes("small") }) { B { Text("추가") } }
            Input(InputType.Text) { value(newName); onInput { newName = it.value }; placeholder("이름"); attr("autocapitalize", "off") }
            Input(InputType.Password) { value(newPassword); onInput { newPassword = it.value }; placeholder("비밀번호 (8자 이상)"); attr("autocomplete", "new-password") }
            Select({ onChange { e -> e.value?.let { newRole = it } } }) {
                listOf("MEMBER", "GUEST").forEach { r -> Option(r, { if (r == newRole) selected() }) { Text(roleName(r)) } }
            }
            Button({ type(ButtonType.Submit); classes("primary") }) { Text("사용자 추가") }
        }
        P({ classes("muted", "small") }) { Text("MEMBER: 문서·웹·이미지·자기 폴더 파일(승인), 셸은 Mac 주인 승인. Apple 앱 없음. GUEST: 대화·웹 검색만. 로그인한 사람: ${me.name}") }
    }
}

/** 일회용 연결 코드와 보내는 방법 */
@Composable
private fun CodeView(code: PairCodeDto) {
    Div({ classes("row", "small") }) {
        Span { B({ classes("mono") }) { Text(code.code) } }
        Span({ classes("muted") }) { Text("${messengerName(code.channel)} · ${code.how} · ${code.expiresInSeconds / 60}분 동안") }
    }
}

/** 내 메신저 연결 (M20, U-02): 연결 코드 만들기, 연결된 계정·끊기. 누구나 */
@Composable
fun MessengersCard() {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<MessengersDto?>(null) }
    var codes by remember { mutableStateOf<Map<String, PairCodeDto>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() = scope.launch {
        runCatching { Http.get("/api/me/messengers", MessengersDto.serializer()) }.onSuccess { state = it; error = null }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { load() }
    val s = state ?: return
    if (s.enabled.isEmpty() && s.links.isEmpty()) return
    Div({ classes("card") }) {
        Div({ classes("row") }) { Span { B { Text("내 메신저") } }; Button({ classes("link", "small"); onClick { load() } }) { Text("새로 고침") } }
        error?.let { P({ classes("error", "small") }) { Text(it) } }
        s.links.forEach { link ->
            Div({ classes("row", "small") }) {
                Span { Text("${messengerName(link.channel)} 연결됨 · ${link.externalId}") }
                Button({
                    classes("link", "small")
                    onClick {
                        if (!window.confirm("${messengerName(link.channel)} 연결을 끊을까요?")) return@onClick
                        scope.launch { runCatching { Http.delete("/api/me/messengers/${link.channel}/${link.externalId}") }.onFailure { error = it.message }; load() }
                    }
                }) { Text("끊기") }
            }
        }
        s.enabled.forEach { ch ->
            Div({ classes("row", "small") }) {
                Span { Text(messengerName(ch) + if (s.links.any { it.channel == ch }) " (다른 계정도 연결 가능)" else "") }
                Button({
                    classes("link", "small")
                    onClick {
                        scope.launch {
                            runCatching { Http.post("/api/me/messengers/$ch/pair", "{}", PairCodeDto.serializer()) }
                                .onSuccess { codes = codes + (ch to it); error = null }.onFailure { error = it.message }
                        }
                    }
                }) { Text("연결 코드 만들기") }
            }
            codes[ch]?.let { CodeView(it) }
        }
    }
}

private fun messengerName(channel: String) = when (channel) {
    "TELEGRAM" -> "Telegram"
    "DISCORD" -> "Discord"
    "SLACK" -> "Slack"
    else -> channel
}

/** 본인 비밀번호 바꾸기 (U-02). 바꾸면 모든 기기의 로그인이 풀린다 */
@Composable
fun PasswordCard() {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var sending by remember { mutableStateOf(false) }
    Div({ classes("card") }) {
        Div({ classes("row") }) {
            Span { B { Text("내 비밀번호") } }
            Button({ classes("link", "small"); onClick { open = !open } }) { Text(if (open) "닫기" else "바꾸기") }
        }
        message?.let { (ok, text) -> P({ classes(if (ok) "good" else "error", "small") }) { Text(text) } }
        if (open) Form(attrs = {
            classes("stack")
            addEventListener("submit") { event ->
                event.preventDefault()
                if (sending) return@addEventListener
                sending = true
                scope.launch {
                    runCatching { Http.post("/api/me/password", ApiJson.encodeToString(ChangePasswordDto.serializer(), ChangePasswordDto(current, next))) }
                        // 비밀번호를 바꾸면 이 기기를 포함해 모든 로그인이 풀린다 → 로그인 화면으로
                        .onSuccess { window.alert("바꿨습니다. 새 비밀번호로 다시 로그인하세요."); window.location.reload() }
                        .onFailure { message = false to (it.message ?: "실패") }
                    current = ""; next = ""; sending = false
                }
            }
        }) {
            Input(InputType.Password) { value(current); onInput { current = it.value }; placeholder("지금 비밀번호"); attr("autocomplete", "current-password") }
            Input(InputType.Password) { value(next); onInput { next = it.value }; placeholder("새 비밀번호 (8자 이상)"); attr("autocomplete", "new-password") }
            Button({ type(ButtonType.Submit); classes("primary"); if (sending) disabled() }) { Text("저장") }
        }
    }
}

private fun roleName(role: String) = when (role) {
    "OWNER" -> "Mac 주인"
    "MEMBER" -> "멤버"
    "GUEST" -> "손님"
    else -> role
}
