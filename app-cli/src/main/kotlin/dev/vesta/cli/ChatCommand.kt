package dev.vesta.cli

import dev.vesta.app.AppContainer
import dev.vesta.app.MacSystem

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.agent.AgentEvent
import dev.vesta.core.agent.UserInput
import dev.vesta.core.inference.ChatRole
import dev.vesta.core.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.TimeSource

/** CLI 대화 (PROJECT.md I-01, C-01~C-05). 서버 없이 바로 실행하는 임베디드 모드. */
class ChatCommand : CliktCommand(name = "chat") {
    override fun help(context: Context) = "대화를 시작한다. 생성 중 Ctrl+C로 중단, /help로 명령 보기"

    private val sessionOption by option("--session", "-s", help = "이어서 할 세션 ID")
    private val continueLast by option("--continue", "-c", help = "가장 최근 세션 이어하기").flag()
    private val model by option("--model", "-m", help = "이번 실행에서 쓸 메인 모델 (설정 대신)")
    private val showThinking by option("--show-thinking", help = "추론 모델의 생각 과정 표시").flag()

    private val local by option("--local", help = "서버가 떠 있어도 이 프로세스에서 직접 실행 (임베디드)").flag()
    private val acceptWrites by option("--accept-writes", help = "파일 쓰기·수정은 묻지 않고 승인 (셸은 계속 묻는다). 대화 중 /accept로 바꾼다").flag()
    private val noWorkspace by option("--no-workspace", help = "지금 폴더를 작업 폴더로 쓰지 않는다").flag()

    /** 이번 실행에서 파일 쓰기를 묻지 않고 승인할지 (/accept) */
    private var autoWrites = false

    override fun run() = runBlocking {
        openBackend().use { backend ->
            val terminal = TerminalBuilder.builder().system(true).build()
            val reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("vesta")
                .variable(LineReader.HISTORY_FILE, backend.home.resolve("cli-history"))
                .build()
            val out = terminal.writer()

            var session: Session? = when {
                sessionOption != null -> backend.find(SessionId(sessionOption!!))
                    ?: run { echo("세션을 찾을 수 없습니다: $sessionOption", err = true); throw ProgramResult(1) }
                continueLast -> backend.recent(1).firstOrNull()
                else -> null
            }

            out.println(dim("Vesta · ${backend.description} · /help 명령 보기 · Ctrl+D 종료"))
            autoWrites = acceptWrites
            val workspace = if (noWorkspace) null else Workspaces.current()?.let { ws ->
                if (Workspaces.trusted(ws)) return@let ws
                // 처음 보거나 바뀐 VESTA.md: 따를지 먼저 묻는다. 입력이 터미널이 아니면 묻지 않고 쓰지 않는다
                val what = listOfNotNull(Workspaces.FILE.takeIf { ws.instructions != null }, "스킬 ${ws.skills.size}개".takeIf { ws.skills.isNotEmpty() }).joinToString("·")
                if (Workspaces.hasControlChars(ws.instructions.orEmpty())) {
                    out.println(red("${Workspaces.FILE}에 터미널 제어 문자가 들어 있어 쓰지 않습니다 (화면을 속일 수 있음). 파일을 확인하세요"))
                    return@let ws.copy(instructions = null, skills = emptyList())
                }
                if (System.console() == null) {
                    out.println(dim("${what}이 처음 보거나 바뀐 내용이라 이번에는 쓰지 않습니다 (터미널에서 vesta를 실행해 승인)"))
                    return@let ws.copy(instructions = null, skills = emptyList())
                }
                out.println(bold("이 폴더의 ${what}을 처음 봅니다 (또는 내용이 바뀜). 모델이 이 지침을 따릅니다:"))
                Workspaces.preview(ws, lines = 120).lines().forEach { out.println(dim("│ $it")) }
                out.flush()
                val answer = runCatching { reader.readLine("이 지침을 따를까요? [y/N] ").trim().lowercase() }.getOrDefault("")
                if (answer == "y" || answer == "yes" || answer == "ㅛ") ws.also(Workspaces::trust) else ws.copy(instructions = null, skills = emptyList()).also { out.println(dim("지침·스킬 없이 시작합니다.")) }
            }
            workspace?.let { ws ->
                Workspaces.skippedSkills(ws).takeIf { it.isNotEmpty() }?.let { out.println(red("쓰지 않은 스킬 (형식 오류·숨은 문자·예약된 이름): ${it.joinToString()}")) }
                out.println(dim("작업 폴더 ${Workspaces.display(ws.path)}" + (if (ws.instructions != null) " · ${Workspaces.FILE} 읽음" else "") +
                    (if (ws.skills.isNotEmpty()) " · 스킬 ${ws.skills.joinToString { "/" + it.name }}" else "") +
                    if (autoWrites) " · 파일 쓰기 자동 승인" else ""))
            }
            session?.let { printHistory(out, backend, it) }
            out.flush()
            // 작업 폴더가 등록한 저장소면 바뀐 파일만 다시 색인한다. 기다리지 않고 대화를 시작한다
            // (입력 대기 readLine이 이 스레드를 막으므로 다른 스레드에서)
            val refresh = workspace?.let { ws ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        Workspaces.refreshRepo(ws)?.let { reader.printAbove(dim(it)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reader.printAbove(dim("저장소 색인 갱신 실패: ${e.message}"))
                    }
                }
            }

            while (true) {
                val line = try {
                    reader.readLine("› ")
                } catch (_: UserInterruptException) {
                    break
                } catch (_: EndOfFileException) {
                    break
                }.trim()
                if (line.isEmpty()) continue

                // CLI 명령만 여기서 처리한다. /web·/docs·/chat(라우팅 명령)과 /Users/… 같은 경로로 시작하는 입력은 대화로 넘긴다
                if (line.substringBefore(' ') in CLI_COMMANDS) {
                    val (command, arg) = line.split(Regex("\\s+"), limit = 2).let { it[0] to it.getOrNull(1) }
                    when (command) {
                        "/exit", "/quit" -> break
                        "/help" -> out.println(HELP)
                        "/new" -> {
                            session = null
                            out.println(dim("새 대화를 시작합니다."))
                        }
                        "/sessions" -> listSessions(out, backend)
                        "/resume" -> {
                            val target = resolveSession(backend, arg)
                            if (target == null) out.println(red("세션을 찾을 수 없습니다: ${arg.orEmpty()}"))
                            else {
                                session = target
                                printHistory(out, backend, target)
                            }
                        }
                        "/history" -> session?.let { printHistory(out, backend, it) } ?: out.println(dim("아직 대화가 없습니다."))
                        "/accept" -> {
                            autoWrites = when (arg?.lowercase()) { "on" -> true; "off" -> false; else -> !autoWrites }
                            out.println(dim(if (autoWrites) "파일 쓰기·수정을 묻지 않고 승인합니다 (셸은 계속 묻습니다). 끄기: /accept off" else "파일 쓰기·수정도 매번 묻습니다."))
                        }
                    }
                    out.flush()
                    continue
                }

                session = generate(terminal, reader, backend, session, line, workspace) ?: session
            }
            // 색인이 남았으면 멈춘다 (남은 파일은 다음 실행 때). 터미널을 닫기 전에 끝나야 출력이 꼬이지 않는다
            refresh?.cancel()
            refresh?.join()
            out.println(dim("종료합니다."))
            out.flush()
            terminal.close()
        }
    }

    /** 답변 하나를 스트리밍으로 출력한다. 생성 중 Ctrl+C면 중단한다. 이번 턴의 세션을 돌려준다. */

    /** 서버가 떠 있으면 원격, 아니면(또는 --local·--model) 임베디드 (PROJECT.md 6.2) */
    private suspend fun openBackend(): ChatBackend {
        if (!local && model == null) {
            val config = dev.vesta.core.config.ConfigLoader.load(dev.vesta.core.config.VestaPaths.default().configFile)
            RemoteBackend.connect(config.server.baseUrl, dev.vesta.app.ApiToken.read())?.let { return it }
        }
        val app = AppContainer(generalOverride = model)
        val general = app.config.models.general
        if (general == null || general.name.isBlank()) {
            app.close()
            echo("메인 모델이 설정되지 않았습니다. ~/.vesta/config.yaml의 models.general을 설정하거나 --model을 주세요.", err = true)
            throw ProgramResult(1)
        }
        return EmbeddedBackend(app)
    }

    private suspend fun generate(terminal: Terminal, reader: LineReader, backend: ChatBackend, session: Session?, text: String, workspace: dev.vesta.core.agent.Workspace?): Session? {
        val out = terminal.writer()
        val renderer = StreamRenderer(out, showThinking)
        var current = session
        val start = TimeSource.Monotonic.markNow()
        val job = kotlinx.coroutines.coroutineScope {
            launch {
                backend.send(session?.id, text, workspace).collect { event ->
                    if (event is AgentEvent.SessionReady && event.created) {
                        current = backend.find(event.sessionId)
                    }
                    renderer.render(event, start.elapsedNow())
                    if (event is AgentEvent.Media) saveMedia(backend, event)?.let { out.println(mediaLine(event, it)); out.flush() }
                    if (event is AgentEvent.ApprovalRequested) {
                        val decision = if (autoWrites && AutoApprove.allows(event, workspace?.path)) {
                            out.println(dim("✓ 자동 승인 (파일 쓰기): ${event.details.title}"))
                            dev.vesta.core.tools.ApprovalDecision.Approve
                        } else Approvals.ask(reader, out, event)
                        backend.resolveApproval(event.requestId, decision)
                    }
                }
            }.also { job ->
                val previous = terminal.handle(Terminal.Signal.INT) { job.cancel(CancellationException("사용자 중단")) }
                job.invokeOnCompletion { cause ->
                    terminal.handle(Terminal.Signal.INT, previous)
                    if (cause is CancellationException) renderer.interrupted()
                }
            }
        }
        job.join()
        out.flush()
        return current
    }

    private suspend fun listSessions(out: java.io.PrintWriter, backend: ChatBackend) {
        val recent = backend.recent(10)
        if (recent.isEmpty()) {
            out.println(dim("저장된 세션이 없습니다."))
            return
        }
        recent.forEachIndexed { index, s ->
            out.println("${index + 1}. ${s.title ?: "(제목 없음)"} ${dim("${TIME.format(s.updatedAt)} · ${s.id.value.take(8)}")}")
        }
        out.println(dim("/resume <번호> 로 이어하기"))
    }

    private suspend fun resolveSession(backend: ChatBackend, arg: String?): Session? {
        if (arg.isNullOrBlank()) return backend.recent(1).firstOrNull()
        arg.toIntOrNull()?.let { return backend.recent(10).getOrNull(it - 1) }
        return backend.recent(200).firstOrNull { it.id.value.startsWith(arg) }
    }

    private suspend fun printHistory(out: java.io.PrintWriter, backend: ChatBackend, session: Session) {
        out.println(dim("── ${session.title ?: "대화"} ──"))
        backend.messages(session.id).forEach { m ->
            when (m.role) {
                ChatRole.USER -> out.println(bold("› ") + m.content)
                ChatRole.ASSISTANT -> out.println(m.content + if (m.interrupted) dim(" (중단됨)") else "")
                else -> Unit
            }
        }
        out.println(dim("────"))
    }

    private companion object {
        val CLI_COMMANDS = setOf("/exit", "/quit", "/help", "/new", "/sessions", "/resume", "/history", "/accept")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val HELP = """
            /new             새 대화
            /sessions        최근 세션 목록
            /resume [번호]   세션 이어하기 (번호 생략 시 가장 최근)
            /history         현재 대화 다시 보기
            /web 질문         웹 검색해서 답하기 (질문에 URL이 있으면 그 페이지를 읽음)
            /docs 질문        등록한 모든 문서에서 찾아 답하기
            /code 질문        코드 도우미 (작업 폴더가 등록한 저장소면 그 저장소에서 찾음)
            /task 할 일       작업 에이전트: 파일 읽기·쓰기·셸로 직접 처리 (쓰기·셸은 승인)
            /이름 할 일       스킬 (~/.vesta/skills, 작업 폴더 .vesta/skills, 웹에서 만든 것). /skills 목록
            /agent 이름 할 일 사용자 정의·A2A 에이전트, /team 이름 할 일 팀. /agents 목록
            @컬렉션 질문      지정한 컬렉션에서 찾아 답하기 (@a,b 여러 개)
            /accept [on|off] 파일 쓰기·수정을 묻지 않고 승인 (셸은 계속 묻는다)
            /exit            종료 (Ctrl+D)
            생성 중 Ctrl+C    답변 중단 (그때까지 내용은 저장)
        """.trimIndent()
    }
}

/** 만든 이미지는 `~/Pictures/Vesta/`, 영상은 `~/Movies/Vesta/`에 저장하고 경로를 돌려준다 */
internal suspend fun saveMedia(backend: ChatBackend, event: AgentEvent.Media): java.nio.file.Path? = runCatching {
    val bytes = event.bytes.takeIf { it.isNotEmpty() } ?: backend.media(event.asset.id) ?: return null
    val video = event.asset.mime == "video/mp4"
    val dir = java.nio.file.Path.of(System.getProperty("user.home"), if (video) "Movies" else "Pictures", "Vesta").also { java.nio.file.Files.createDirectories(it) }
    val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    dir.resolve("$stamp-${event.asset.id.take(8)}.${if (video) "mp4" else "png"}").also { java.nio.file.Files.write(it, bytes) }
}.getOrNull()

internal fun mediaLine(event: AgentEvent.Media, path: java.nio.file.Path) = if (event.asset.mime == "video/mp4") "🎬 영상: $path" else "🖼 이미지: $path"
