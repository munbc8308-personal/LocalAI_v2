package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

private const val CHECK_MS = 60_000L

/**
 * 새로 설치하면(웹 판이 바뀌면) 열린 웹을 스스로 새로 고친다. 1분마다, 그리고 탭으로 돌아올 때 본다.
 * 하던 일이 있으면(답을 받는 중·승인 대기·쓰던 글) 날리지 않게 위에 알림만 띄우고, 손을 놓으면 그때 고친다.
 * 같이 [onTick]으로 대화 목록 같은 화면 밖 변화를 다시 불러온다.
 */
@Composable
fun UpdateWatcher(busy: () -> Boolean, onTick: () -> Unit) {
    var loaded by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var wake by remember { mutableStateOf(0) }

    suspend fun check() {
        val build = runCatching { Http.get("/api/build", JsonObject.serializer())["build"]?.jsonPrimitive?.content }.getOrNull() ?: return
        if (loaded == null) loaded = build
        else if (build != loaded) waiting = true
        if (waiting && (document.asDynamic().hidden == true || !busy() && !typing())) window.location.reload()
    }

    DisposableEffect(Unit) {
        val listener: (org.w3c.dom.events.Event) -> Unit = { if (document.asDynamic().hidden != true) wake++ }
        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
    LaunchedEffect(wake) {
        if (wake > 0) onTick()
        while (true) {
            check()
            delay(if (waiting) 5_000 else CHECK_MS)
            onTick()
        }
    }
    if (waiting) Div({ classes("card", "row", "small", "update") }) {
        Span { Text("새 버전이 설치됐습니다. 하던 일이 끝나면 저절로 새로 고칩니다.") }
        Button({ classes("primary", "small"); onClick { window.location.reload() } }) { Text("지금 새로 고침") }
    }
}

/** 입력 칸에 쓰던 글이 있으면 새로 고침을 미룬다 */
private fun typing(): Boolean = when (val e = document.activeElement) {
    is HTMLTextAreaElement -> e.value.isNotBlank()
    is HTMLInputElement -> e.value.isNotEmpty()
    else -> false
}
