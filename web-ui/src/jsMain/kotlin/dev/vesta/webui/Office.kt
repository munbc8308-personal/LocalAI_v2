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
fun OfficeView(userName: String, onOpenSession: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf("지금 일하는 직원이 없습니다. 대화나 에이전트에 일을 맡기면 들어옵니다.") }
    var rooms by remember { mutableStateOf(listOf(Office.Room(Office.MAIN, "본사", 0, false))) }
    var current by remember { mutableStateOf(Office.MAIN) }
    val office = remember { Office(userName) { count -> info = if (count == 0) "지금 일하는 직원이 없습니다. 대화나 에이전트에 일을 맡기면 들어옵니다." else "일하는 직원 ${count}명 · 직원을 누르면 그 대화로" } }
    office.onRooms = { rooms = it }
    Div({ classes("page") }) {
        H2 { Text("사무실") }
        // 방(본사·팀마다): 일하는 직원 수, 승인 기다리면 빨간 점
        Div({ classes("row", "small"); style { property("flex-wrap", "wrap"); property("justify-content", "flex-start"); property("gap", "6px") } }) {
            rooms.forEach { r ->
                org.jetbrains.compose.web.dom.Button({
                    classes("link", "small"); if (r.key == current) classes("on")
                    onClick { current = r.key; office.show(r.key) }
                }) { Text(r.label + (if (r.count > 0) " (${r.count})" else "") + if (r.approval) " 🔴" else "") }
            }
        }
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
        scope.launch { runCatching { Http.get("/api/me/settings", dev.vesta.api.SettingsDto.serializer()) }.getOrNull()?.avatar?.let { office.avatar = it } }
        // 팀마다 방 (꾸미기는 팀 설정, 없으면 팀 이름으로)
        scope.launch { runCatching { Http.get("/api/agents", dev.vesta.api.AgentsDto.serializer()) }.getOrNull()?.teams?.forEach { office.addRoom(it.name, it.theme) } }
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
internal class Office(private val userName: String, private val onCount: (Int) -> Unit) {
    /** 내 캐릭터 꾸미기 (설정, M38) */
    var avatar: String? = null
    /** 방 탭에 보일 것 (M38 OF-09) */
    data class Room(val key: String, val label: String, val count: Int, val approval: Boolean)
    var onRooms: (List<Room>) -> Unit = {}
    /** 방 → 꾸미기. 본사는 사무실 */
    private val themes = linkedMapOf(MAIN to "office")
    private var room = MAIN

    fun addRoom(team: String, theme: String?) {
        themes[team] = theme ?: THEMES[(team.hashCode() and 0x7fffffff) % THEMES.size]
        publish()
    }

    fun show(key: String) { room = key }

    private fun publish() = onRooms(themes.keys.map { k ->
        val inRoom = workers.values.filter { it.room == k && !it.leaving }
        Room(k, if (k == MAIN) "본사" else "팀 $k", inRoom.count { !it.member }, inRoom.any { it.approval })
    })
    private class Station(val key: String, val col: Double, val row: Double, val icon: String, val label: String)
    private class Envelope(var x: Double, var y: Double, val tx: Double, val ty: Double, val room: String = MAIN)

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
        /** 끝나면 사용자에게 보고할 말 (답 첫 줄) */
        var report: String? = null
        var reporting = false
        var reportLeft = 0.0
        /** 같은 에이전트가 동시에 여럿이면 2, 3… */
        var nth = 1
        /** 어느 방 (본사 또는 팀 이름) */
        var room = MAIN
        /** 메인 모델이 이 직원 일로 생각·글쓰기 중 (도구·승인 대기가 아닐 때) */
        var thinking = false
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
        Station("boss", 8.0, 8.6, "", ""),
    ).associateBy { it.key }
    /** 책상 자리 (칸 단위) */
    private val desks = (0 until 12).map { i -> (2.6 + (i % 6) * 2.1) to (4.0 + (i / 6) * 2.4) }
    private val workers = LinkedHashMap<Long, Worker>()
    /** 팀 실행에서 지금 맡은 멤버 (멤버의 단계는 멤버가 움직인다) */
    private val activeMembers = HashMap<Long, HashMap<String, Long>>()
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
            workers.values.firstOrNull { it.room == room && abs(it.x * tile - px) < tile * 0.5 && abs(it.y * tile - py) < tile * 0.8 && !it.member }
                ?.let { onClick(it.run) }
        }
        frame = window.requestAnimationFrame(::tick)
    }

    /** 설정 화면 미리 보기: 내 캐릭터 하나만 */
    fun preview(c: HTMLCanvasElement) {
        tile = 72.0
        c.width = 120; c.height = 140
        val g = c.getContext("2d") as CanvasRenderingContext2D
        g.clearRect(0.0, 0.0, 120.0, 140.0)
        g.asDynamic().imageSmoothingEnabled = false
        drawPerson(g, 60 / tile, 112 / tile, "USER", 0.0, 0.0, 99)
    }

    fun detach() {
        window.cancelAnimationFrame(frame)
        canvas = null
    }

    fun enter(run: Long, agent: String = "CHAT", name: String? = null, atDesk: Boolean = false) {
        if (workers.containsKey(run)) return
        val door = stations.getValue("door")
        val w = Worker(run, agent, name, door.col, door.row)
        w.nth = renumber(agent, name)
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
            // 팀: 멤버가 한 단계는 "[이름] …"·"이름/도구"로 온다 (여럿이 동시에 일할 수 있다, OF-11)
            val memberName = Regex("^\\[([^\\]]+)\\]").find(e.title)?.groupValues?.get(1)
                ?: e.title.removePrefix("도구 ").substringBefore(' ').takeIf { '/' in it }?.substringBefore('/')
            val actor = memberName?.let { activeMembers[run]?.get(it) }?.let { workers[it] } ?: leader
            when (e.kind) {
                "routed" -> {
                    leader.agent = u.agent ?: leader.agent
                    leader.name = u.customAgent
                    leader.skill = u.skill
                    leader.nth = renumber(leader.agent, leader.name, except = leader.run)
                    if (leader.agent == "TEAM") leader.name?.let { moveTo(leader, it) }
                }
                "status" -> actor.bubble = e.title.replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
                "tool_started" -> {
                    val tool = e.title.removePrefix("도구 ").substringBefore(' ').substringAfter('/')
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
                    val who = e.title.removePrefix("나눠 맡김: ").substringBefore("에게").substringBefore(':').trim().take(12)
                    val m = Worker(id, "MEMBER", who, mail.col, mail.row).apply { member = true; parent = run; room = leader.room }
                    m.desk = freeDesk(leader.room)
                    desks.getOrNull(m.desk)?.let { (c, r) -> m.tx = c; m.ty = r }
                    workers[id] = m
                    activeMembers.getOrPut(run) { HashMap() }[who] = id
                    envelopes += Envelope(leader.x, leader.y, m.tx, m.ty, leader.room)
                }
                "delegate_finished" -> {
                    // "받음: 이름: …" — 그 멤버만 보낸다 (여럿이 일하는 중일 수 있다)
                    val who = e.title.substringAfter(": ").substringBefore(':').trim()
                    val ids = activeMembers[run] ?: HashMap()
                    (ids.remove(who) ?: ids.keys.firstOrNull()?.let { ids.remove(it) })
                        ?.let { id -> workers[id]?.let { m -> envelopes += Envelope(m.x, m.y, leader.x, leader.y, leader.room); leave(m) } }
                    back(leader)
                    leader.bubble = e.title
                }
                // 답 첫머리의 "…하겠습니다·할게요" 같은 계획 줄은 건너뛰고 결과 줄을 보고한다
                "answer" -> leader.report = (e.detail ?: e.title).lines().map(::plain).filter { it.isNotBlank() }
                    .let { lines -> lines.firstOrNull { !INTENT.containsMatchIn(it) } ?: lines.firstOrNull() }
                "error" -> leader.report = "오류가 났어요"
                "done" -> leader.bubble = "보고하러 가요"
            }
            if (e.kind != "approval" && e.kind != "status") actor.approval = false
            // 메인 모델이 생각·글쓰기 중인지 (도구가 도는 동안·승인 대기는 아니다)
            actor.thinking = e.kind in setOf("input", "routed", "status", "tool_finished", "delegate_finished")
            if (e.kind in setOf("answer", "done", "error")) leader.thinking = false
            publish()
        }
        u.run?.let { r ->
            val w = workers[r.id] ?: if (r.status == "RUNNING") { enter(r.id, r.agent, r.customAgent); workers[r.id] } else {
                // 아주 짧은 실행(백그라운드 일 등): 들어왔다 바로 나간다
                enter(r.id, r.agent, r.customAgent); workers[r.id]?.also { it.bubble = r.input.take(20) }
            }
            if (w != null) {
                w.agent = r.agent
                w.name = r.customAgent ?: w.name
                if (r.status != "RUNNING") report(w)
            }
        }
    }

    private fun freeDesk(inRoom: String = MAIN): Int {
        val used = workers.values.filter { !it.leaving && it.room == inRoom }.map { it.desk }.toSet()
        return desks.indices.firstOrNull { it !in used } ?: -1
    }

    /** 팀 일이면 그 팀 방으로 옮겨 앉힌다 (문에서 다시 들어온다) */
    private fun moveTo(w: Worker, target: String) {
        if (w.room == target) return
        if (target != MAIN && target !in themes) addRoom(target, null)
        w.room = target
        val door = stations.getValue("door")
        w.x = door.col; w.y = door.row
        w.desk = freeDesk(target)
        back(w)
        publish()
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

    /** 말풍선용: 마크다운 표시·출처 번호를 뺀다 */
    private fun plain(text: String) = text.replace(Regex("\\s*\\[\\d+(?:\\s*,\\s*\\d+)*\\]"), "").replace(Regex("[*_`#>]+"), "").trim()

    /** 사용자 책상 앞으로 가서 보고한 뒤 나간다 (멤버는 리더에게 편지로 보고했으니 바로) */
    private fun report(w: Worker) {
        if (w.member || w.reporting || w.leaving) { if (w.member) leave(w); return }
        w.reporting = true
        w.approval = false
        val boss = stations.getValue("boss")
        w.tx = boss.col + ((w.run % 3) - 1) * 0.9; w.ty = boss.row - 1.3
        w.reportLeft = REPORT_SECONDS
    }

    /** 같은 이름으로 지금 있는 직원 수 + 1 */
    private fun renumber(agent: String, name: String?, except: Long? = null): Int =
        workers.values.count { it.run != except && !it.leaving && it.agent == agent && it.name == name } + 1

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
            if (w.reporting && !moving) {
                w.bubble = "보고: " + (w.report ?: "끝났어요")
                w.reportLeft -= dt
                if (w.reportLeft <= 0) { w.reporting = false; leave(w) }
            }
        }
        if (gone.isNotEmpty()) { gone.forEach { workers.remove(it) }; onCount(workers.values.count { !it.member }); publish() }
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
        val theme = THEME_LOOK.getValue(themes[room] ?: "office")
        for (row in 0 until 10) for (col in 0 until 16) {
            g.fillStyle = when {
                row < 2 -> theme.wall
                theme.planks -> if ((col + (row % 2) * 2) % 4 == 0) theme.floorB else theme.floorA
                else -> if ((row + col) % 2 == 0) theme.floorA else theme.floorB
            }
            g.fillRect(col * tile, row * tile, tile + 0.5, tile + 0.5)
        }
        g.fillStyle = theme.trim; g.fillRect(0.0, 2 * tile - 4, width, 4.0)
        // 방 장식 (테마마다)
        g.font = "${(tile * 0.55).toInt()}px sans-serif"; g.fillStyle = "#000"
        for ((c0, r0, icon) in theme.decor) g.fillText(icon, c0 * tile, r0 * tile)
        if (room != MAIN) { g.font = "${(tile * 0.3).toInt()}px sans-serif"; g.fillStyle = "#fff"; g.fillText("팀 $room · ${theme.label}", 8.0 * tile, 0.45 * tile) }
        // 자리
        g.asDynamic().textAlign = "center"
        for (s in stations.values.filter { it.icon.isNotEmpty() }) {
            g.fillStyle = "rgba(0,0,0,0.12)"
            g.fillRect((s.col - 0.6) * tile, (s.row - 0.5) * tile, tile * 1.2, tile * 1.1)
            g.font = "${(tile * 0.6).toInt()}px sans-serif"
            g.fillStyle = "#000"
            g.fillText(s.icon, s.col * tile, (s.row + 0.25) * tile)
            g.font = "${(tile * 0.24).toInt()}px sans-serif"; g.fillStyle = if (s.row < 2) "#eee" else "#555"
            g.fillText(s.label, s.col * tile, (s.row + 0.62) * tile)
        }
        // 사용자 책상과 사용자
        val boss = stations.getValue("boss")
        // 메인 모델이 어느 직원 일로든 생각·글쓰기 중이면 사용자가 타자를 친다 (모델 = 나의 머리)
        val typing = workers.values.any { it.thinking && !it.leaving && !it.reporting }
        drawPerson(g, boss.col, boss.row - 0.35, "USER", if (typing) t * 14 else 0.0, t, 99)
        if (typing) {
            g.fillStyle = "#9be7ff"; g.fillRect((boss.col + 0.55) * tile, (boss.row - 0.35) * tile, tile * 0.55, tile * 0.3)
            g.font = "${(tile * 0.22).toInt()}px sans-serif"; g.fillStyle = "#333"
            pill(g, "생각하는 중" + ".".repeat(((t * 2).toInt() % 3) + 1), boss.col * tile, (boss.row - 1.55) * tile, "#eef6ff", "#245")
        }
        g.fillStyle = "#6b4a2e"; g.fillRect((boss.col - 1.3) * tile, (boss.row - 0.1) * tile, tile * 2.6, tile * 0.6)
        g.fillStyle = "#c9a227"; g.fillRect((boss.col - 1.3) * tile, (boss.row - 0.1) * tile, tile * 2.6, tile * 0.08)
        g.font = "${(tile * 0.24).toInt()}px sans-serif"; g.fillStyle = "#fff"
        g.fillText("$userName (나)", boss.col * tile, (boss.row + 0.33) * tile)
        workers.values.any { it.reporting && abs(it.ty - it.y) < 0.05 && abs(it.tx - it.x) < 0.05 }.takeIf { it }?.let { pill(g, "👍", boss.col * tile, (boss.row - 1.15) * tile, "#fff", "#333") }
        // 직원 (아래쪽이 앞에 오게), 책상은 앉은 직원 앞에 그린다
        // 앉은 직원은 책상 뒤에, 걸어다니는·보고하는 직원은 책상 앞에
        val (seated, walking) = workers.values.filter { it.room == room }.sortedBy { it.y }.partition { w -> desks.getOrNull(w.desk)?.let { (c0, r0) -> abs(w.x - c0) < 0.05 && abs(w.y - r0) < 0.05 } == true }
        seated.forEach { drawWorker(g, it, t) }
        for ((c0, r0) in desks) {
            g.fillStyle = "#2f3542"; g.fillRect((c0 + 0.15) * tile, (r0 - 0.05) * tile, tile * 0.45, tile * 0.32)
            g.fillStyle = "#9b6b43"; g.fillRect((c0 - 0.7) * tile, (r0 + 0.25) * tile, tile * 1.4, tile * 0.45)
        }
        walking.forEach { drawWorker(g, it, t) }
        // 편지
        g.font = "${(tile * 0.45).toInt()}px sans-serif"
        for (e in envelopes.filter { it.room == room }) g.fillText("✉️", e.x * tile, e.y * tile)
    }

    private fun drawWorker(g: CanvasRenderingContext2D, w: Worker, t: Double) {
        val px = tile / 12
        val y = drawPerson(g, w.x, w.y - 0.25, w.agent, w.phase, t, w.run, w.name)
        // 이름: 페르소나 이름 · 역할 (사용자 정의 에이전트는 그 이름)
        val title = displayName(w)
        g.font = "${(tile * 0.21).toInt()}px sans-serif"
        g.fillStyle = "rgba(255,255,255,0.85)"
        val tw = g.measureText(title).width + 6
        g.fillRect(w.x * tile - tw / 2, y - 4.2 * px, tw, tile * 0.26)
        g.fillStyle = "#222"; g.fillText(title, w.x * tile, y - 4.2 * px + tile * 0.2)
        // 이웃끼리 말풍선이 겹치지 않게 번갈아 높이를 바꾼다
        val lift = if (w.desk % 2 == 1) tile * 0.42 else 0.0
        w.skill?.let { s -> pill(g, "/$s", w.x * tile, y - 9.5 * px - lift, "#5b4bd6", "#fff") }
        if (w.approval) pill(g, "! 승인 필요", w.x * tile, y - 4.8 * px - lift, "#e5484d", "#fff")
        else w.bubble?.takeIf { it.isNotBlank() }?.let { b -> val n = if (w.reporting) 60 else 40; pill(g, b.take(n) + if (b.length > n) "…" else "", w.x * tile, y - 4.8 * px - lift, if (w.reporting) "#fff8d6" else "#fff", "#333") }
    }

    private fun displayName(w: Worker): String {
        val base = w.name ?: "${persona(w.agent)} · ${label(w.agent)}"
        return if (w.nth > 1) "$base ${w.nth}" else base
    }

    /**
     * 픽셀 사람 하나 (12×18 칸). 역할마다 머리·모자·소품·옷이 다르다. 그린 머리 위 y를 돌려준다.
     * [seed]로 사용자 정의 에이전트는 이름마다 색이 달라진다
     */
    private fun drawPerson(g: CanvasRenderingContext2D, cx: Double, cy: Double, agent: String, phase: Double, t: Double, seed: Long, name: String? = null): Double {
        val px = tile / 12
        val bob = if (phase > 0) sin(phase) * px * 0.7 else sin(t * 2 + seed) * px * 0.25
        val x0 = cx * tile - 6 * px
        val y0 = cy * tile - 12 * px + bob
        fun r(x: Double, y: Double, w: Double, h: Double, c: String) { g.fillStyle = c; g.fillRect(x0 + x * px, y0 + y * px, w * px, h * px) }
        val look = look(agent, name)
        val step = if (phase > 0 && sin(phase * 2) > 0) 1.0 else 0.0
        // 그림자
        g.fillStyle = "rgba(0,0,0,0.15)"; g.fillRect(x0 + 2 * px, y0 + 17.2 * px, 8 * px, 1 * px)
        // 다리·신발
        r(3.5, 13.0, 2.0, 3.5 + step, look.pants); r(6.5, 13.0, 2.0, 4.5 - step, look.pants)
        r(3.0, 16.5 + step, 2.8, 1.0, "#222"); r(6.2, 17.5 - step, 2.8, 1.0, "#222")
        // 몸통·팔
        r(2.5, 7.5, 7.0, 6.0, look.shirt)
        r(1.3, 8.0, 1.3, 4.5, look.shirt); r(9.4, 8.0, 1.3, 4.5, look.shirt)
        r(1.3, 12.3, 1.3, 1.0, SKIN); r(9.4, 12.3, 1.3, 1.0, SKIN)
        when (look.outfit) {
            "tie" -> { r(4.0, 7.5, 4.0, 1.2, "#fff"); r(5.5, 8.5, 1.0, 3.5, look.accent) }
            "hoodie" -> { r(3.0, 7.0, 6.0, 1.3, look.accent); r(5.2, 8.3, 0.4, 2.0, "#ddd"); r(6.4, 8.3, 0.4, 2.0, "#ddd"); r(4.0, 11.0, 4.0, 1.5, look.accent) }
            "vest" -> { r(2.5, 7.5, 2.0, 6.0, look.accent); r(7.5, 7.5, 2.0, 6.0, look.accent) }
            "overall" -> { r(3.5, 9.5, 5.0, 4.0, look.accent); r(3.8, 7.5, 0.8, 2.2, look.accent); r(7.4, 7.5, 0.8, 2.2, look.accent) }
            "apron" -> r(3.5, 9.0, 5.0, 4.6, look.accent)
            "cardigan" -> { r(5.6, 7.5, 0.8, 6.0, look.accent); r(4.4, 9.0, 0.4, 0.4, "#fff"); r(4.4, 10.5, 0.4, 0.4, "#fff") }
            "crown" -> { r(4.0, 7.5, 4.0, 1.0, look.accent) }
        }
        // 목·얼굴
        r(5.0, 6.5, 2.0, 1.2, SKIN)
        r(3.0, 1.5, 6.0, 5.3, SKIN)
        r(4.2, 3.8, 0.9, 0.9, "#222"); r(6.9, 3.8, 0.9, 0.9, "#222")
        r(5.3, 5.5, 1.4, 0.5, "#c0645a")
        r(3.6, 4.9, 0.7, 0.4, "#f3a9a0"); r(7.7, 4.9, 0.7, 0.4, "#f3a9a0")
        // 머리 모양
        when (look.hair) {
            "short" -> { r(2.8, 0.6, 6.4, 1.6, look.hairColor); r(2.8, 1.5, 0.9, 2.2, look.hairColor); r(8.3, 1.5, 0.9, 2.2, look.hairColor) }
            "bun" -> { r(2.8, 0.8, 6.4, 1.5, look.hairColor); r(4.8, -0.9, 2.4, 1.8, look.hairColor); r(2.6, 1.6, 0.9, 4.0, look.hairColor); r(8.5, 1.6, 0.9, 4.0, look.hairColor) }
            "long" -> { r(2.6, 0.6, 6.8, 1.7, look.hairColor); r(2.2, 1.5, 1.2, 6.0, look.hairColor); r(8.6, 1.5, 1.2, 6.0, look.hairColor) }
            "spiky" -> { r(2.8, 0.9, 6.4, 1.3, look.hairColor); r(3.0, 0.0, 1.0, 1.0, look.hairColor); r(5.0, -0.4, 1.0, 1.4, look.hairColor); r(7.0, 0.0, 1.0, 1.0, look.hairColor) }
        }
        // 모자·소품
        when (look.hat) {
            "explorer" -> { r(2.0, 0.6, 8.0, 0.9, "#8a6d3b"); r(3.3, -0.9, 5.4, 1.6, "#a0824a") }
            "hardhat" -> { r(2.4, 0.2, 7.2, 1.4, "#f2c94c"); r(3.4, -0.9, 5.2, 1.2, "#f2c94c"); r(1.8, 1.3, 8.4, 0.5, "#e0b23a") }
            "beret" -> { r(2.6, -0.2, 6.6, 1.5, "#c0392b"); r(5.6, -0.9, 0.8, 0.8, "#c0392b") }
            "cap" -> { r(2.8, 0.0, 6.4, 1.4, "#34495e"); r(0.8, 0.8, 2.2, 0.6, "#34495e") }
            "crown" -> { r(3.2, -0.8, 5.6, 1.2, "#f1c40f"); r(3.2, -1.6, 0.9, 0.9, "#f1c40f"); r(5.55, -1.8, 0.9, 1.0, "#f1c40f"); r(7.9, -1.6, 0.9, 0.9, "#f1c40f") }
        }
        when (look.gear) {
            "glasses" -> { r(3.8, 3.5, 1.7, 1.4, "rgba(255,255,255,0.35)"); r(6.5, 3.5, 1.7, 1.4, "rgba(255,255,255,0.35)"); r(3.8, 3.4, 4.4, 0.3, "#222") }
            "headphones" -> { r(2.0, 2.5, 1.0, 2.6, "#222"); r(9.0, 2.5, 1.0, 2.6, "#222"); r(2.4, 0.3, 7.2, 0.6, "#222") }
            "earpiece" -> { r(8.8, 3.6, 0.7, 1.2, "#222"); r(8.0, 4.8, 1.0, 0.3, "#222") }
            "book" -> { r(9.2, 10.0, 2.4, 3.0, "#2e86de"); r(9.6, 10.3, 1.6, 0.4, "#fff") }
            "palette" -> { r(-0.4, 10.0, 2.6, 2.0, "#e0c097"); r(0.0, 10.4, 0.6, 0.6, "#e74c3c"); r(0.9, 10.4, 0.6, 0.6, "#3498db"); r(0.4, 11.1, 0.6, 0.6, "#2ecc71") }
            "camera" -> { r(9.0, 9.5, 2.8, 2.0, "#2d3436"); r(9.9, 9.9, 1.0, 1.0, "#74b9ff") }
            "laptop" -> { r(-0.6, 10.5, 3.0, 2.0, "#636e72"); r(-0.4, 10.7, 2.6, 1.4, "#81ecec") }
        }
        return y0
    }

    /** 설정의 꾸미기 값으로 (`top=hoodie,#7d4cdb;bottom=#263238;hair=long,#4a2f1d;acc=glasses`), 없으면 왕관 쓴 정장 */
    private fun userLook(): Look {
        val m = avatar.orEmpty().split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].split(',') } }.toMap()
        val top = m["top"]; val hair = m["hair"]; val acc = m["acc"]?.firstOrNull() ?: "crown"
        val shirt = top?.getOrNull(1) ?: "#2c3e50"
        val hat = acc.takeIf { it in setOf("crown", "cap", "beret", "explorer") } ?: ""
        val gear = acc.takeIf { it in setOf("glasses", "headphones", "earpiece") } ?: ""
        val outfit = when (top?.firstOrNull()) { null -> "crown"; "tee" -> ""; else -> top.first() }
        return Look(shirt, m["bottom"]?.firstOrNull() ?: "#1e272e", if (outfit == "crown") "#f1c40f" else darker(shirt), outfit,
            hair?.firstOrNull() ?: "short", hair?.getOrNull(1) ?: "#2b1b10", hat, gear)
    }

    /** 옷 장식 색: 바탕보다 조금 어둡게 */
    private fun darker(hex: String): String {
        val v = hex.removePrefix("#").toIntOrNull(16) ?: return "#333333"
        fun ch(shift: Int) = ((v shr shift and 0xff) * 0.7).toInt()
        return "#" + listOf(16, 8, 0).joinToString("") { ch(it).toString(16).padStart(2, '0') }
    }

    private class Look(val shirt: String, val pants: String, val accent: String, val outfit: String, val hair: String, val hairColor: String, val hat: String = "", val gear: String = "")

    private fun look(agent: String, name: String?): Look = when (agent) {
        "USER" -> userLook()
        "CHAT" -> Look("#4f8ef7", "#34495e", "#2f6fd6", "cardigan", "long", "#4a2f1d")
        "WEB" -> Look("#2bb673", "#5d4037", "#1e8c57", "vest", "short", "#3b2a1a", "explorer", "glasses")
        "DOCS" -> Look("#f2994a", "#4e342e", "#d97a2b", "cardigan", "bun", "#c58b4c", gear = "book")
        "CODE" -> Look("#7d4cdb", "#263238", "#5a32a8", "hoodie", "spiky", "#1b1b1b", gear = "headphones")
        "TASK" -> Look("#e8eef3", "#2c3e50", "#eb5757", "overall", "short", "#5a2d0c", "hardhat")
        "PERSONAL" -> Look("#2d3436", "#2d3436", "#e2b93b", "tie", "short", "#111", gear = "earpiece")
        "IMAGE" -> Look("#dff3fb", "#455a64", "#3cb4e5", "apron", "long", "#8e5a2b", "beret", "palette")
        "VIDEO" -> Look("#9b59b6", "#2c3e50", "#6c3483", "hoodie", "short", "#2b2b2b", "cap", "camera")
        "TEAM" -> Look("#1e5631", "#1e272e", "#27ae60", "tie", "short", "#3b2a1a", gear = "laptop")
        "CURATOR", "SKILL" -> Look("#95a5a6", "#34495e", "#7f8c8d", "apron", "short", "#555")
        else -> {
            // 사용자 정의 에이전트: 이름마다 색이 달라진다
            val h = (name ?: agent).hashCode().let { if (it < 0) -it else it }
            val shirts = listOf("#e67e22", "#16a085", "#c0392b", "#2980b9", "#8e44ad", "#d35400")
            Look(shirts[h % shirts.size], "#2c3e50", "#ecf0f1", listOf("tie", "vest", "hoodie", "cardigan")[h % 4], listOf("short", "long", "spiky", "bun")[(h / 7) % 4], listOf("#2b1b10", "#6d4c41", "#111", "#b9770e")[(h / 3) % 4])
        }
    }

    /** 말풍선: 넓이를 넘으면 두 줄까지 (넘치면 …). 아래쪽이 [y] */
    private fun pill(g: CanvasRenderingContext2D, text: String, x: Double, y: Double, bg: String, fg: String) {
        g.font = "${(tile * 0.24).toInt()}px sans-serif"
        val maxWidth = tile * 3.4
        val lines = mutableListOf<String>()
        var line = ""
        for (ch in text) {
            if (g.measureText(line + ch).width > maxWidth) {
                if (lines.size == 1) { line = line.dropLast(1) + "…"; break }
                lines += line; line = ""
            }
            line += ch
        }
        lines += line
        val w = lines.maxOf { g.measureText(it).width } + tile * 0.3
        // 방 밖으로 잘리지 않게 안쪽으로 민다
        val x = x.coerceIn(w / 2 + 2, tile * 16 - w / 2 - 2)
        val lh = tile * 0.3
        val h = lh * lines.size + tile * 0.1
        g.fillStyle = bg; g.fillRect(x - w / 2, y - h, w, h)
        g.strokeStyle = "rgba(0,0,0,0.25)"; g.strokeRect(x - w / 2, y - h, w, h)
        g.fillStyle = fg
        lines.forEachIndexed { i, l -> g.fillText(l, x, y - h + tile * 0.05 + lh * (i + 0.78)) }
    }

    private fun persona(agent: String) = when (agent) {
        "CHAT" -> "하나"; "WEB" -> "탐이"; "DOCS" -> "서윤"; "CODE" -> "코다"; "TASK" -> "맥스"; "PERSONAL" -> "미소"
        "IMAGE" -> "아라"; "VIDEO" -> "무비"; "TEAM" -> "팀장"; "CURATOR" -> "모아"; "SKILL" -> "스미스"; else -> agent.lowercase()
    }

    private fun label(agent: String) = when (agent) {
        "CHAT" -> "대화"; "WEB" -> "웹"; "DOCS" -> "문서"; "CODE" -> "코드"; "TASK" -> "작업"; "PERSONAL" -> "비서"
        "IMAGE" -> "이미지"; "VIDEO" -> "영상"; "TEAM" -> "팀"; "CURATOR" -> "학습"; "SKILL" -> "스킬"; else -> agent.lowercase()
    }

    private class Theme(val label: String, val wall: String, val trim: String, val floorA: String, val floorB: String, val planks: Boolean, val decor: List<Triple<Double, Double, String>>)

    companion object {
        const val MAIN = ""
        val THEMES = listOf("office", "cafe", "lab", "library", "studio", "factory")
        private val THEME_LOOK = mapOf(
            "office" to Theme("사무실", "#3d3a4f", "#2b2838", "#e8dcc3", "#e0d3b8", false, listOf(Triple(0.6, 3.2, "🪴"), Triple(15.4, 3.2, "🪴"), Triple(0.6, 6.0, "🗓️"))),
            "cafe" to Theme("카페", "#5d4037", "#3e2723", "#c8a27a", "#b8915f", true, listOf(Triple(0.6, 3.2, "🪴"), Triple(15.4, 3.2, "☕"), Triple(15.4, 6.0, "🪴"), Triple(0.6, 6.0, "🧁"))),
            "lab" to Theme("연구소", "#cfd8dc", "#90a4ae", "#f5f7f8", "#e7ecef", false, listOf(Triple(0.6, 3.2, "🧪"), Triple(15.4, 3.2, "🔬"), Triple(0.6, 6.0, "🧬"), Triple(15.4, 6.0, "⚗️"))),
            "library" to Theme("도서관", "#4e342e", "#3e2723", "#8e3b46", "#7d3340", false, listOf(Triple(0.6, 3.2, "📚"), Triple(15.4, 3.2, "📚"), Triple(0.6, 6.0, "🕯️"), Triple(15.4, 6.0, "📜"))),
            "studio" to Theme("스튜디오", "#1f1f2e", "#000000", "#3a3a4a", "#33334a", false, listOf(Triple(0.6, 3.2, "💡"), Triple(15.4, 3.2, "🎬"), Triple(0.6, 6.0, "🎧"), Triple(15.4, 6.0, "🎛️"))),
            "factory" to Theme("공장", "#546e7a", "#37474f", "#9e9e9e", "#8f8f8f", false, listOf(Triple(0.6, 3.2, "⚙️"), Triple(15.4, 3.2, "📦"), Triple(0.6, 6.0, "🔧"), Triple(15.4, 6.0, "🏗️"))),
        )
        const val SKIN = "#f1c9a5"
        private val INTENT = Regex("(하겠습니다|할게요|할게|맡기겠|드리겠|해 볼게|찾아볼게)[.!]?$")
        const val REPORT_SECONDS = 2.5
    }
}
