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
    fun load() = scope.launch {
        runCatching { Http.get("/api/users", ListSerializer(UserDto.serializer())) }
            .onSuccess { users = it; error = null }
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
        Form(attrs = {
            classes("stack")
            addEventListener("submit") { event ->
                event.preventDefault()
                if (newName.isBlank()) return@addEventListener
                act("${newName.trim()} 을(를) 추가했습니다. 로그인 이름: ${newName.trim()}") {
                    Http.post("/api/users", ApiJson.encodeToString(NewUserDto.serializer(), NewUserDto(newName.trim(), newRole, newPassword.ifEmpty { null })))
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
        P({ classes("muted", "small") }) { Text("MEMBER: 문서·웹·이미지·자기 폴더 파일(승인). Apple 앱·셸 없음. GUEST: 대화·웹 검색만. 로그인한 사람: ${me.name}") }
    }
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
