package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.vesta.api.ApiJson
import dev.vesta.api.SampleReviewDto
import dev.vesta.api.TrainingSampleDto
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * 학습 재료 검토 (PROJECT.md L-26, M25): 큐레이터가 모은 재료 중 불확실한 것을 맞음·틀림·고치기.
 * 확실한 재료(👍·👎, 확실함 0.9 이상)는 이미 쓰이고, 여기에는 검토 대기만 나온다
 */
@Composable
fun SamplesCard(counts: Map<String, Int>, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<TrainingSampleDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        runCatching { Http.get("/api/training/samples?status=PENDING", ListSerializer(TrainingSampleDto.serializer())) }
            .onSuccess { pending = it; error = null }.onFailure { error = it.message }
    }
    fun review(id: Long, status: String, label: String? = null) = scope.launch {
        runCatching { Http.post("/api/training/samples/$id", ApiJson.encodeToString(SampleReviewDto.serializer(), SampleReviewDto(status, label))) }
            .onFailure { error = it.message }
        tick++
        onChanged()
    }
    Div({ classes("card") }) {
        Div({ classes("row") }) {
            Span { B { Text("학습 재료") } }
            Span {
                Span({ classes("muted", "small") }) { Text("쉬는 때 모읍니다 (동의한 사용자만) ") }
                Button({ classes("link", "small"); onClick { scope.launch { runCatching { Http.post("/api/training/curate") }.onFailure { error = it.message }; tick++; onChanged() } } }) { Text("지금 모으기") }
            }
        }
        Div({ classes("small") }) {
            Text(listOf("ROUTING" to "라우팅", "ANSWER" to "답 평가", "TOOL" to "도구").joinToString(" · ") { (kind, label) ->
                "$label ${counts["$kind:ACCEPTED"] ?: 0}" + ((counts["$kind:PENDING"] ?: 0).takeIf { it > 0 }?.let { " (검토 대기 $it)" } ?: "")
            })
        }
        error?.let { P({ classes("error", "small") }) { Text(it) } }
        if (pending.isEmpty()) P({ classes("muted", "small") }) { Text("검토할 재료가 없습니다.") }
        pending.take(20).forEach { s -> SampleRow(s, onReview = { status, label -> review(s.id, status, label) }) }
    }
}

@Composable
private fun SampleRow(s: TrainingSampleDto, onReview: (String, String?) -> Unit) {
    var label by remember(s.id) { mutableStateOf(s.label) }
    Div({ classes("run") }) {
        Div({ classes("row", "small") }) {
            Span { B { Text(kindLabel(s.kind) + " · " + signalLabel(s.signal)) }; Text(" · ${s.user ?: "-"}") }
            Span({ classes("muted") }) { Text("확실함 ${(s.confidence * 100).toInt()}%") }
        }
        Div({ classes("small") }) { Text("질문: ${s.question.take(200)}") }
        s.answer?.let { Div({ classes("muted", "small") }) { Text("답: ${it.take(200)}") } }
        s.note?.let { Div({ classes("muted", "small") }) { Text(it.take(200)) } }
        Div({ classes("row", "small") }) {
            Span {
                Text(if (s.kind == "ROUTING") "담당: " else if (s.kind == "ANSWER") "평가: " else "라벨: ")
                when (s.kind) {
                    "ROUTING" -> Select({ onChange { e -> label = e.value ?: label } }) {
                        listOf("CHAT", "DOCS", "WEB", "CODE", "TASK", "PERSONAL", "IMAGE").forEach { v -> Option(v, { if (v == label) selected() }) { Text(v) } }
                    }
                    "ANSWER" -> Select({ onChange { e -> label = e.value ?: label } }) {
                        listOf("good" to "좋음", "bad" to "나쁨").forEach { (v, t) -> Option(v, { if (v == label) selected() }) { Text(t) } }
                    }
                    else -> Text(s.label)
                }
            }
            Span {
                Button({ classes("link", "small"); onClick { onReview("ACCEPTED", label.takeIf { it != s.label }) } }) { Text(if (label != s.label) "고쳐서 맞음" else "맞음") }
                Button({ classes("link", "small", "bad"); onClick { onReview("REJECTED", null) } }) { Text("틀림") }
            }
        }
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "ROUTING" -> "라우팅"
    "ANSWER" -> "답 평가"
    else -> "도구"
}

private fun signalLabel(signal: String) = when (signal) {
    "FEEDBACK" -> "👍·👎"
    "REASK" -> "담당을 지정해 다시 물음"
    "REPHRASE" -> "말을 바꿔 다시 물음"
    "CORRECTION" -> "바로잡음"
    "DENIAL" -> "승인 거부"
    else -> signal
}
