package dev.vesta.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.vesta.api.ApiJson
import dev.vesta.api.RunDto
import dev.vesta.api.RunUpdateDto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.compose.web.dom.Canvas
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.abs
import kotlin.math.sin

/**
 * 사무실 화면 (PROJECT.md 7.32, M38). 실행 하나가 직원 하나: 실행 소식(`/api/runs/stream`)대로 들어와 앉고, 도구마다 자리로 걸어가 일하고, 끝나면 나간다.
 * 여러 실행이 동시에 돌면 직원도 동시에 여럿. 팀 리더가 맡기면 멤버 직원이 생겨 편지가 오간다. 그림은 모두 코드로 그린다(픽셀 직원·가구)
 */
@Composable
fun OfficeView(onOpenSession: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf("지금 일하는 직원이 없습니다. 대화나 에이전트에 일을 맡기면 들어옵니다.") }
    val office = remember { Office { count -> info = if (count == 0) "지금 일하는 직원이 없습니다. 대화나 에이전트에 일을 맡기면 들어옵니다." else "일하는 직원 ${count}명 · 직원을 누르면 그 대화로" } }
    Div({ classes("page") }) {
        H2 { Text("사무실") }
        P({ classes("muted", "small") }) { Text(info) }
        Canvas({ id("office"); classes("office") }) {}
    }
    DisposableEffect(Unit) {
        val canvas = document.getElementById("office") as HTMLCanvasElement
        office.attach(canvas) { run ->
            scope.launch {
                runCatching { Http.get("/api/runs?limit=100", ListSerializer(RunDto.serializer())) }.getOrNull()
                    ?.firstOrNull { it.id == run }?.sessionId?.let(onOpenSession)
            }
        }
        scope.launch {
            runCatching { Http.get("/api/runs?limit=30", ListSerializer(RunDto.serializer())) }.getOrNull()
                ?.filter { it.status == "RUNNING" }?.forEach { office.enter(it.id, it.agent, it.customAgent, atDesk = true) }
        }
        val source: dynamic = js("new EventSource('/api/runs/stream')")
        source.onmessage = { message: dynamic ->
            val text = message.data as String
            if (text != "{}") runCatching { ApiJson.decodeFromString(RunUpdateDto.serializer(), text) }.getOrNull()?.let(office::update)
        }
        onDispose {
            source.close()
            office.detach()
        }
    }
}

/** 사무실 상태와 그리기. Compose 상태 대신 매 프레임 직접 그린다 (직원이 많아도 가볍게) */
private class Office(private val onCount: (Int) -> Unit) {
    private class Station(val key: String, val col: Double, val row: Double, val icon: String, val label: String)
    private class Envelope(var x: Double, var y: Double, val tx: Double, val ty: Double)

    private class Worker(val run: Long, var agent: String, var name: String?, var x: Double, var y: Double) {
        var tx = x
        var ty = y
        var desk = -1
        var bubble: String? = null
        var skill: String? = null
        var approval = false
        var leaving = false
        var member = false
        var parent: Long? = null
        var phase = 0.0
    }

    private val stations = listOf(
        Station("door", 0.6, 8.4, "🚪", "문"),
        Station("computer", 2.0, 1.2, "💻", "웹"),
        Station("books", 4.4, 1.2, "📚", "책장"),
        Station("files", 6.8, 1.2, "🗄️", "서류"),
        Station("terminal", 9.2, 1.2, "⌨️", "터미널"),
        Station("easel", 11.6, 1.2, "🎨", "이젤"),
        Station("phone", 14.0, 1.2, "📞", "전화"),
        Station("mail", 14.6, 8.4, "📮", "우편함"),
    ).associateBy { it.key }
    /** 책상 자리 (칸 단위) */
    private val desks = (0 until 12).map { i -> (2.6 + (i % 6) * 2.1) to (4.0 + (i / 6) * 2.4) }
    private val workers = LinkedHashMap<Long, Worker>()
    /** 팀 실행에서 지금 맡은 멤버 (멤버의 단계는 멤버가 움직인다) */
    private val activeMember = HashMap<Long, Long>()
    private val envelopes = mutableListOf<Envelope>()
    private var nextMemberId = -1L
    private var canvas: HTMLCanvasElement? = null
    private var frame = 0
    private var last = 0.0
    private var tile = 40.0

    fun attach(target: HTMLCanvasElement, onClick: (Long) -> Unit) {
        canvas = target
        target.onclick = { e ->
            val rect = target.getBoundingClientRect()
            val px = (e.asDynamic().clientX as Double) - rect.left
            val py = (e.asDynamic().clientY as Double) - rect.top
            workers.values.firstOrNull { abs(it.x * tile - px) < tile * 0.5 && abs(it.y * tile - py) < tile * 0.8 && !it.member }
                ?.let { onClick(it.run) }
        }
        frame = window.requestAnimationFrame(::tick)
    }

    fun detach() {
        window.cancelAnimationFrame(frame)
        canvas = null
    }

    fun enter(run: Long, agent: String = "CHAT", name: String? = null, atDesk: Boolean = false) {
        if (workers.containsKey(run)) return
        val door = stations.getValue("door")
        val w = Worker(run, agent, name, door.col, door.row)
        w.desk = freeDesk()
        desks.getOrNull(w.desk)?.let { (c, r) -> w.tx = c; w.ty = r; if (atDesk) { w.x = c; w.y = r } }
        workers[run] = w
        onCount(workers.values.count { !it.member })
    }

    fun update(u: RunUpdateDto) {
        u.event?.let { e ->
            val run = e.run
            if (e.kind == "input") enter(run)
            val leader = workers[run] ?: run { enter(run); workers.getValue(run) }
            // 팀: 맡긴 동안의 단계는 그 멤버가 한다
            val actor = activeMember[run]?.let { workers[it] } ?: leader
            when (e.kind) {
                "routed" -> {
                    leader.agent = u.agent ?: leader.agent
                    leader.name = u.customAgent
                    leader.skill = u.skill
                }
                "status" -> actor.bubble = e.title
                "tool_started" -> {
                    val tool = e.title.removePrefix("도구 ").substringBefore(' ')
                    goTo(actor, stationFor(tool, actor.agent))
                    actor.bubble = e.detail ?: tool
                }
                "tool_finished" -> back(actor)
                "approval" -> { actor.approval = true; actor.bubble = e.title.removePrefix("승인 요청: ") }
                "delegate_started" -> {
                    goTo(leader, "mail")
                    leader.bubble = e.title
                    val id = nextMemberId--
                    val mail = stations.getValue("mail")
                    val m = Worker(id, "TEAM", e.title.removePrefix("나눠 맡김: ").substringBefore(':').take(12), mail.col, mail.row).apply { member = true; parent = run }
                    m.desk = freeDesk()
                    desks.getOrNull(m.desk)?.let { (c, r) -> m.tx = c; m.ty = r }
                    workers[id] = m
                    activeMember[run] = id
                    envelopes += Envelope(leader.x, leader.y, m.tx, m.ty)
                }
                "delegate_finished" -> {
                    activeMember.remove(run)?.let { id -> workers[id]?.let { m -> envelopes += Envelope(m.x, m.y, leader.x, leader.y); leave(m) } }
                    back(leader)
                    leader.bubble = e.title
                }
                "done", "answer", "error" -> leader.bubble = if (e.kind == "error") "오류" else "끝"
            }
            if (e.kind != "approval" && e.kind != "status") actor.approval = false
        }
        u.run?.let { r ->
            val w = workers[r.id] ?: if (r.status == "RUNNING") { enter(r.id, r.agent, r.customAgent); workers[r.id] } else {
                // 아주 짧은 실행(백그라운드 일 등): 들어왔다 바로 나간다
                enter(r.id, r.agent, r.customAgent); workers[r.id]?.also { it.bubble = r.input.take(20) }
            }
            if (w != null) {
                w.agent = r.agent
                w.name = r.customAgent ?: w.name
                if (r.status != "RUNNING") leave(w)
            }
        }
    }

    private fun freeDesk(): Int {
        val used = workers.values.filter { !it.leaving }.map { it.desk }.toSet()
        return desks.indices.firstOrNull { it !in used } ?: -1
    }

    private fun stationFor(tool: String, agent: String): String = when {
        tool in setOf("web_search", "read_url") -> "computer"
        tool.startsWith("search_") && tool != "search_files" -> "books"
        tool in setOf("read_file", "write_file", "list_files", "search_files", "apply_patch", "move_file", "make_directory") -> "files"
        tool == "run_shell" -> "terminal"
        tool.contains("image") || tool.contains("video") || agent == "IMAGE" || agent == "VIDEO" -> "easel"
        tool == "update_plan" || tool == "current_time" -> "desk"
        agent == "PERSONAL" || '_' in tool -> "phone"
        else -> "desk"
    }

    private fun goTo(w: Worker, key: String) {
        if (key == "desk") { back(w); return }
        stations[key]?.let { s -> w.tx = s.col + (w.run % 3) * 0.25; w.ty = s.row + 0.9 }
    }

    private fun back(w: Worker) {
        desks.getOrNull(w.desk)?.let { (c, r) -> w.tx = c; w.ty = r }
    }

    private fun leave(w: Worker) {
        w.leaving = true
        w.approval = false
        val door = stations.getValue("door")
        w.tx = door.col; w.ty = door.row
    }

    private fun tick(now: Double) {
        val c = canvas ?: return
        val dt = if (last == 0.0) 0.016 else ((now - last) / 1000).coerceAtMost(0.1)
        last = now
        step(dt)
        draw(c, now / 1000)
        frame = window.requestAnimationFrame(::tick)
    }

    private fun step(dt: Double) {
        val speed = 3.2 * dt
        val gone = mutableListOf<Long>()
        for (w in workers.values) {
            // 먼저 가로, 그다음 세로로 걷는다 (픽셀 게임처럼)
            val dx = w.tx - w.x
            val dy = w.ty - w.y
            val moving = abs(dx) > 0.02 || abs(dy) > 0.02
            if (abs(dx) > 0.02) w.x += dx.coerceIn(-speed, speed) else if (abs(dy) > 0.02) w.y += dy.coerceIn(-speed, speed)
            w.phase = if (moving) w.phase + dt * 10 else 0.0
            if (w.leaving && !moving) gone += w.run
        }
        if (gone.isNotEmpty()) { gone.forEach { workers.remove(it) }; onCount(workers.values.count { !it.member }) }
        envelopes.removeAll { e ->
            val dx = e.tx - e.x; val dy = e.ty - e.y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < 0.2) true else { e.x += dx / d * 6 * dt; e.y += dy / d * 6 * dt; false }
        }
    }

    private fun draw(c: HTMLCanvasElement, t: Double) {
        val width = (c.parentElement?.clientWidth ?: 640).coerceAtMost(980).toDouble()
        val ratio = window.devicePixelRatio
        tile = width / 16
        val height = tile * 10
        if (c.width != (width * ratio).toInt()) {
            c.width = (width * ratio).toInt(); c.height = (height * ratio).toInt()
            c.style.width = "${width}px"; c.style.height = "${height}px"
        }
        val g = c.getContext("2d") as CanvasRenderingContext2D
        g.setTransform(ratio, 0.0, 0.0, ratio, 0.0, 0.0)
        g.asDynamic().imageSmoothingEnabled = false
        // 바닥·벽
        for (row in 0 until 10) for (col in 0 until 16) {
            g.fillStyle = if (row < 2) "#3d3a4f" else if ((row + col) % 2 == 0) "#e8dcc3" else "#e0d3b8"
            g.fillRect(col * tile, row * tile, tile + 0.5, tile + 0.5)
        }
        g.fillStyle = "#2b2838"; g.fillRect(0.0, 2 * tile - 4, width, 4.0)
        // 자리
        g.asDynamic().textAlign = "center"
        for (s in stations.values) {
            g.fillStyle = "rgba(0,0,0,0.12)"
            g.fillRect((s.col - 0.6) * tile, (s.row - 0.5) * tile, tile * 1.2, tile * 1.1)
            g.font = "${(tile * 0.6).toInt()}px sans-serif"
            g.fillStyle = "#000"
            g.fillText(s.icon, s.col * tile, (s.row + 0.25) * tile)
            g.font = "${(tile * 0.24).toInt()}px sans-serif"; g.fillStyle = if (s.row < 2) "#eee" else "#555"
            g.fillText(s.label, s.col * tile, (s.row + 0.62) * tile)
        }
        // 직원 (아래쪽이 앞에 오게), 책상은 앉은 직원 앞에 그린다
        for (w in workers.values.sortedBy { it.y }) drawWorker(g, w, t)
        for ((c0, r0) in desks) {
            g.fillStyle = "#2f3542"; g.fillRect((c0 + 0.15) * tile, (r0 - 0.05) * tile, tile * 0.45, tile * 0.32)
            g.fillStyle = "#9b6b43"; g.fillRect((c0 - 0.7) * tile, (r0 + 0.25) * tile, tile * 1.4, tile * 0.45)
        }
        // 앉은 직원 이름표는 책상 위에 보이게
        g.font = "${(tile * 0.22).toInt()}px sans-serif"; g.fillStyle = "#fff"
        for (w in workers.values) desks.getOrNull(w.desk)?.let { (c0, r0) -> if (abs(w.x - c0) < 0.05 && abs(w.y - r0) < 0.05) g.fillText((w.name ?: label(w.agent)).take(10), c0 * tile, (r0 + 0.58) * tile) }
        // 편지
        g.font = "${(tile * 0.45).toInt()}px sans-serif"
        for (e in envelopes) g.fillText("✉️", e.x * tile, e.y * tile)
    }

    private fun drawWorker(g: CanvasRenderingContext2D, w: Worker, t: Double) {
        val px = tile / 10
        val x = w.x * tile
        val bob = if (w.phase > 0) sin(w.phase) * px * 0.6 else sin(t * 2 + w.run) * px * 0.2
        val y = w.y * tile - tile * 0.2 + bob
        fun box(dx: Double, dy: Double, ww: Double, hh: Double, color: String) { g.fillStyle = color; g.fillRect(x + dx * px, y + dy * px, ww * px, hh * px) }
        val body = color(w.agent)
        val step = if (w.phase > 0 && sin(w.phase * 2) > 0) 1.0 else 0.0
        box(-2.0, 3.0, 1.6, 2.5 + step, "#333")             // 다리
        box(0.4, 3.0, 1.6, 3.5 - step, "#333")
        box(-3.0, -2.0, 6.0, 5.2, body)                      // 몸
        box(-2.5, -6.5, 5.0, 4.6, "#f1c9a5")                 // 얼굴
        box(-2.8, -7.3, 5.6, 1.8, hair(w.agent))             // 머리
        box(-1.4, -4.8, 0.8, 0.8, "#222"); box(0.6, -4.8, 0.8, 0.8, "#222")
        // 이름표
        g.font = "${(tile * 0.22).toInt()}px sans-serif"; g.fillStyle = "#333"
        g.fillText((w.name ?: label(w.agent)).take(10), x, y + 7.8 * px)
        // 스킬 배지
        w.skill?.let { s -> pill(g, "/$s", x, y - 10.5 * px, "#5b4bd6", "#fff") }
        // 말풍선·승인
        if (w.approval) pill(g, "! 승인 필요", x, y - 8.5 * px, "#e5484d", "#fff")
        else w.bubble?.takeIf { it.isNotBlank() }?.let { pill(g, it.take(18) + if (it.length > 18) "…" else "", x, y - 8.5 * px, "#fff", "#333") }
    }

    private fun pill(g: CanvasRenderingContext2D, text: String, x: Double, y: Double, bg: String, fg: String) {
        g.font = "${(tile * 0.24).toInt()}px sans-serif"
        val w = g.measureText(text).width + tile * 0.3
        val h = tile * 0.38
        g.fillStyle = bg; g.fillRect(x - w / 2, y - h, w, h)
        g.strokeStyle = "rgba(0,0,0,0.25)"; g.strokeRect(x - w / 2, y - h, w, h)
        g.fillStyle = fg; g.fillText(text, x, y - h * 0.28)
    }

    private fun color(agent: String) = when (agent) {
        "CHAT" -> "#4f8ef7"; "WEB" -> "#2bb673"; "DOCS" -> "#f2994a"; "CODE" -> "#9b51e0"; "TASK" -> "#eb5757"
        "PERSONAL" -> "#e2b93b"; "IMAGE" -> "#3cb4e5"; "VIDEO" -> "#bb6bd9"; "TEAM" -> "#27ae60"; else -> "#8a8f98"
    }

    private fun hair(agent: String) = when (agent) { "WEB", "CODE" -> "#3b2a1a"; "DOCS", "IMAGE" -> "#d8a25e"; "TASK" -> "#5a2d0c"; else -> "#2b2b2b" }

    private fun label(agent: String) = when (agent) {
        "CHAT" -> "대화"; "WEB" -> "웹"; "DOCS" -> "문서"; "CODE" -> "코드"; "TASK" -> "작업"; "PERSONAL" -> "비서"
        "IMAGE" -> "이미지"; "VIDEO" -> "영상"; "TEAM" -> "팀"; "CURATOR" -> "학습"; "SKILL" -> "스킬"; else -> agent.lowercase()
    }
}
