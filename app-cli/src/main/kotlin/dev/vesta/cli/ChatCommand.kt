package dev.vesta.cli

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

    override fun run() = runBlocking {
        AppContainer(generalOverride = model).use { app ->
            val general = app.config.models.general
            if (general == null || general.name.isBlank()) {
                echo("메인 모델이 설정되지 않았습니다. ~/.vesta/config.yaml의 models.general을 설정하거나 --model을 주세요.", err = true)
                throw ProgramResult(1)
            }
            val terminal = TerminalBuilder.builder().system(true).build()
            val reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("vesta")
                .variable(LineReader.HISTORY_FILE, app.paths.home.resolve("cli-history"))
                .build()
            val out = terminal.writer()

            var session: Session? = when {
                sessionOption != null -> app.sessions.find(app.owner.userId, SessionId(sessionOption!!))
                    ?: run { echo("세션을 찾을 수 없습니다: $sessionOption", err = true); throw ProgramResult(1) }
                continueLast -> app.sessions.listRecent(app.owner.userId, 1).firstOrNull()
                else -> null
            }

            out.println(dim("Vesta · 모델 ${general.name}${general.reasoningEffort?.let { " (생각 $it)" }.orEmpty()} · /help 명령 보기 · Ctrl+D 종료"))
            session?.let { printHistory(out, app, it) }
            out.flush()

            while (true) {
                val line = try {
                    reader.readLine("› ")
                } catch (_: UserInterruptException) {
                    break
                } catch (_: EndOfFileException) {
                    break
                }.trim()
                if (line.isEmpty()) continue

                // /web /docs /chat 은 라우팅 명령이라 대화 엔진으로 넘긴다
                if (line.startsWith("/") && line.substringBefore(' ') !in ROUTING_COMMANDS) {
                    val (command, arg) = line.split(Regex("\\s+"), limit = 2).let { it[0] to it.getOrNull(1) }
                    when (command) {
                        "/exit", "/quit" -> break
                        "/help" -> out.println(HELP)
                        "/new" -> {
                            session = null
                            out.println(dim("새 대화를 시작합니다."))
                        }
                        "/sessions" -> listSessions(out, app)
                        "/resume" -> {
                            val target = resolveSession(app, arg)
                            if (target == null) out.println(red("세션을 찾을 수 없습니다: ${arg.orEmpty()}"))
                            else {
                                session = target
                                printHistory(out, app, target)
                            }
                        }
                        "/history" -> session?.let { printHistory(out, app, it) } ?: out.println(dim("아직 대화가 없습니다."))
                        else -> out.println(red("알 수 없는 명령: $command (/help)"))
                    }
                    out.flush()
                    continue
                }

                session = generate(terminal, app, session, line) ?: session
            }
            out.println(dim("종료합니다."))
            out.flush()
            terminal.close()
        }
    }

    /** 답변 하나를 스트리밍으로 출력한다. 생성 중 Ctrl+C면 중단한다. 이번 턴의 세션을 돌려준다. */
    private suspend fun generate(terminal: Terminal, app: AppContainer, session: Session?, text: String): Session? {
        val out = terminal.writer()
        val renderer = StreamRenderer(out, showThinking)
        var current = session
        val start = TimeSource.Monotonic.markNow()
        val job = kotlinx.coroutines.coroutineScope {
            launch {
                app.conversation.send(app.owner, ChannelType.CLI, session?.id, UserInput(text)).collect { event ->
                    if (event is AgentEvent.SessionReady && event.created) {
                        current = app.sessions.find(app.owner.userId, event.sessionId)
                    }
                    renderer.render(event, start.elapsedNow())
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

    private suspend fun listSessions(out: java.io.PrintWriter, app: AppContainer) {
        val recent = app.sessions.listRecent(app.owner.userId, 10)
        if (recent.isEmpty()) {
            out.println(dim("저장된 세션이 없습니다."))
            return
        }
        recent.forEachIndexed { index, s ->
            out.println("${index + 1}. ${s.title ?: "(제목 없음)"} ${dim("${TIME.format(s.updatedAt)} · ${s.id.value.take(8)}")}")
        }
        out.println(dim("/resume <번호> 로 이어하기"))
    }

    private suspend fun resolveSession(app: AppContainer, arg: String?): Session? {
        if (arg.isNullOrBlank()) return app.sessions.listRecent(app.owner.userId, 1).firstOrNull()
        arg.toIntOrNull()?.let { return app.sessions.listRecent(app.owner.userId, 10).getOrNull(it - 1) }
        return app.sessions.listRecent(app.owner.userId, 200).firstOrNull { it.id.value.startsWith(arg) }
    }

    private suspend fun printHistory(out: java.io.PrintWriter, app: AppContainer, session: Session) {
        out.println(dim("── ${session.title ?: "대화"} ──"))
        app.messages.list(session.id).forEach { m ->
            when (m.role) {
                ChatRole.USER -> out.println(bold("› ") + m.content)
                ChatRole.ASSISTANT -> out.println(m.content + if (m.interrupted) dim(" (중단됨)") else "")
                else -> Unit
            }
        }
        out.println(dim("────"))
    }

    private companion object {
        val ROUTING_COMMANDS = setOf("/web", "/docs", "/chat")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val HELP = """
            /new             새 대화
            /sessions        최근 세션 목록
            /resume [번호]   세션 이어하기 (번호 생략 시 가장 최근)
            /history         현재 대화 다시 보기
            /web 질문         웹 검색해서 답하기 (질문에 URL이 있으면 그 페이지를 읽음)
            /docs 질문        등록한 모든 문서에서 찾아 답하기
            @컬렉션 질문      지정한 컬렉션에서 찾아 답하기 (@a,b 여러 개)
            /exit            종료 (Ctrl+D)
            생성 중 Ctrl+C    답변 중단 (그때까지 내용은 저장)
        """.trimIndent()
    }
}
