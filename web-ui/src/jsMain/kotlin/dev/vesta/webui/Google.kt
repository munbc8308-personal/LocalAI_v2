package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.vesta.api.GoogleStatusDto
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Serializable
private class ConnectDto(val url: String)

/** Google 연결 (M40 GW-02): 자기 Google 계정을 연결·끊기. 연결하면 개인 비서가 Gmail·드라이브·시트·문서·캘린더를 쓴다 */
@Composable
fun GoogleCard() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GoogleStatusDto?>(null) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    fun load() = scope.launch { runCatching { Http.get("/api/google", GoogleStatusDto.serializer()) }.onSuccess { status = it } }
    LaunchedEffect(Unit) {
        // Google에서 돌아왔을 때의 결과 (?google=ok|denied|이유). 보여 준 뒤 주소에서 지운다
        val params = js("new URLSearchParams(window.location.search)")
        (params.get("google") as String?)?.let { result ->
            message = when (result) {
                "ok" -> true to "Google 계정을 연결했습니다"
                "denied" -> false to "연결을 취소했습니다"
                else -> false to result
            }
            window.history.replaceState(null, "", window.location.pathname)
        }
        load()
    }
    val s = status ?: return
    if (!s.available) return
    Div({ classes("card") }) {
        Div({ classes("row") }) { Span { B { Text("Google") } }; Span({ classes("muted", "small") }) { Text("Gmail · 드라이브 · 시트 · 문서 · 캘린더") } }
        message?.let { (ok, text) -> P({ classes(if (ok) "good" else "error", "small") }) { Text(text) } }
        Div({ classes("row", "small") }) {
            Span { Text(s.email?.let { "연결됨 · $it" } ?: "연결 안 함") }
            if (s.email == null) {
                Button({
                    classes("primary", "small")
                    onClick {
                        scope.launch {
                            runCatching { Http.post("/api/google/connect", "{}", ConnectDto.serializer()) }
                                .onSuccess { window.location.href = it.url }
                                .onFailure { message = false to (it.message ?: "연결을 시작하지 못했습니다") }
                        }
                    }
                }) { Text("Google 연결") }
            } else {
                Button({
                    classes("link", "small", "bad")
                    onClick {
                        if (!window.confirm("Google 연결을 끊을까요? Vesta가 받은 권한도 취소합니다")) return@onClick
                        scope.launch {
                            runCatching { Http.delete("/api/google") }.onSuccess { message = true to "연결을 끊었습니다"; load() }.onFailure { message = false to (it.message ?: "실패") }
                        }
                    }
                }) { Text("끊기") }
            }
        }
        P({ classes("muted", "small") }) {
            Text("자기 계정만 씁니다. 읽기는 바로 하고, 메일 보내기·파일 만들기·시트 고치기·일정 만들기는 매번 승인을 받습니다. 로그인할 때 \"확인되지 않은 앱\" 화면이 나오면 고급 > 계속을 누르세요.")
        }
    }
}
