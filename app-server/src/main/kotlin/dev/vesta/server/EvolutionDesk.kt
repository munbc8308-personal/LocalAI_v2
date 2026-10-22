package dev.vesta.server

import dev.vesta.api.EvolutionRunDto
import dev.vesta.api.EvolutionStateDto
import dev.vesta.api.ProposalDto
import dev.vesta.api.ProposalResultDto
import dev.vesta.channels.ButtonStyle
import dev.vesta.channels.ChannelActions
import dev.vesta.channels.ChannelButton
import dev.vesta.channels.ChannelNotifier
import dev.vesta.core.UserContext
import dev.vesta.core.UserId
import dev.vesta.core.evolution.EvolutionRepository
import dev.vesta.core.evolution.EvolutionRun
import dev.vesta.core.evolution.EvolutionStatus
import dev.vesta.core.evolution.Proposal
import dev.vesta.core.evolution.ProposalKind
import dev.vesta.core.evolution.ProposalStatus
import dev.vesta.evolution.EvolutionService
import dev.vesta.evolution.ProposalOutcome
import dev.vesta.identity.RolePolicy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

/**
 * 주간 자가 개선의 서버 쪽 (PROJECT.md 7.13 E-05·E-07, M16).
 * - 점검 결과와 제안을 Mac 주인의 메신저로 보낸다. 메인 모델 교체는 [승인]/[거절], 바로 반영한 임계값은 [되돌리기] 버튼
 * - 버튼·웹 UI·CLI 어디서 처리해도 결과는 `tuned.properties` 한 곳에 남는다
 * - 그 파일이 바뀌면, 하던 답변·학습이 없을 때 서버를 끝낸다. launchd(KeepAlive)가 다시 띄우며 새 설정을 읽는다
 */
class EvolutionDesk(
    private val repo: EvolutionRepository,
    private val service: EvolutionService,
    /** 알림을 받을 사람: Mac 주인 */
    private val owner: UserId,
    private val enabled: Boolean,
    private val tunedFile: Path,
    private val notifiers: () -> List<ChannelNotifier>,
    /** 하던 답변·학습이 있으면 true (그동안은 다시 시작하지 않는다) */
    private val busy: suspend () -> Boolean,
    /** launchd가 띄운 서버일 때만 스스로 끝낸다 (직접 띄웠으면 다시 띄울 주체가 없다) */
    private val underLaunchd: Boolean = System.getenv("XPC_SERVICE_NAME") == "dev.vesta.server",
    private val exit: () -> Unit = { exitProcess(0) },
) : ChannelActions {
    override val prefix = "ev"
    private val loaded = snapshot()

    private fun snapshot(): String = runCatching { Files.readString(tunedFile) }.getOrDefault("")

    /** 읽어 들인 뒤 설정이 바뀌어 다시 시작을 기다리는 중 */
    fun restartPending(): Boolean = snapshot() != loaded

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    deliver()
                    maybeRestart()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "자가 개선 알림 처리 실패" }
                }
                delay(POLL_MILLIS)
            }
        }
    }

    /** 알리지 않은 점검 결과와 제안을 보낸다. 받을 메신저가 없어도 보낸 것으로 친다 (웹 UI·CLI에서 볼 수 있다) */
    suspend fun deliver() {
        for (run in repo.unnotifiedRuns()) {
            send("run:${run.id}", runText(run), emptyList())
            repo.markRunNotified(run.id)
        }
        for (proposal in repo.unnotified()) {
            when {
                proposal.status == ProposalStatus.PENDING -> send(
                    "ev:${proposal.id}", "Vesta 주간 점검 — 승인 요청\n\n" + EvolutionService.describe(proposal),
                    listOf(ChannelButton("승인", "ev:${proposal.id}:a", ButtonStyle.PRIMARY), ChannelButton("거절", "ev:${proposal.id}:r", ButtonStyle.DANGER)),
                )
                proposal.status == ProposalStatus.APPLIED && proposal.kind == ProposalKind.TUNING -> send(
                    "ev:${proposal.id}", "Vesta 주간 점검 — 설정을 바꿨습니다\n\n" + EvolutionService.describe(proposal),
                    listOf(ChannelButton("되돌리기", "ev:${proposal.id}:u", ButtonStyle.DANGER)),
                )
                // CLI·웹 UI에서 이미 처리한 제안은 알리지 않는다
                else -> Unit
            }
            repo.markNotified(proposal.id)
        }
    }

    private suspend fun send(key: String, text: String, buttons: List<ChannelButton>) {
        val sent = notifiers().sumOf { runCatching { it.notify(owner, key, text, buttons) }.getOrDefault(0) }
        log.info { "자가 개선 알림 $key → 대화방 ${sent}곳" }
    }

    /** 설정이 바뀌었고 하던 일이 없으면 끝낸다 (launchd가 다시 띄운다) */
    suspend fun maybeRestart() {
        if (!restartPending() || busy()) return
        if (!underLaunchd) {
            if (!warned) log.warn { "설정(${tunedFile.fileName})이 바뀌었습니다. 서버를 다시 시작해야 반영됩니다" }
            warned = true
            return
        }
        log.info { "설정(${tunedFile.fileName})이 바뀌어 서버를 다시 시작합니다 (launchd가 다시 띄움)" }
        exit()
    }

    @Volatile private var warned = false

    /** 메신저 버튼 `ev:<번호>:<a 승인|r 거절|u 되돌리기>` */
    override suspend fun onButton(user: UserContext, data: String): String {
        val parts = data.split(':')
        val id = parts.getOrNull(1)?.toLongOrNull() ?: return "잘못된 버튼입니다"
        return decide(user, id, when (parts.getOrNull(2)) { "a" -> "approve"; "r" -> "reject"; "u" -> "rollback"; else -> "" }).message
    }

    /** 웹 UI·메신저 공통. Mac 주인만 */
    suspend fun decide(user: UserContext, id: Long, action: String): ProposalResultDto {
        if (!RolePolicy.canManage(user.role)) return ProposalResultDto(false, "설정 변경은 Mac 주인만 할 수 있습니다")
        val outcome: ProposalOutcome = when (action) {
            "approve" -> service.approve(id)
            "reject" -> service.reject(id)
            "rollback" -> service.rollback(id)
            else -> return ProposalResultDto(false, "알 수 없는 동작입니다: $action")
        }
        if (outcome.ok && !underLaunchd && action != "reject") return ProposalResultDto(true, outcome.message.substringBefore(". 하던") + ". 서버를 다시 시작하면 반영됩니다")
        return ProposalResultDto(outcome.ok, outcome.message)
    }

    suspend fun state(): EvolutionStateDto = EvolutionStateDto(
        enabled = enabled,
        lastRun = repo.runs(1).firstOrNull()?.dto(),
        proposals = repo.proposals(10).map { it.dto() },
        tuned = service.current(),
        restartPending = restartPending(),
    )

    private fun runText(run: EvolutionRun): String = when (run.status) {
        EvolutionStatus.FAILED -> "Vesta 주간 점검이 실패했습니다: ${run.error ?: "이유 모름"}\n자세히: vesta evolve list" +
            (run.report?.let { "\n\n" + plain(it) }.orEmpty())
        else -> plain(run.report ?: "Vesta 주간 점검을 마쳤습니다")
    }

    /** 메신저용: 마크다운 머리글·굵게 표시를 뗀다 */
    private fun plain(markdown: String) = markdown.replace("**", "").replace(Regex("^#+ ", RegexOption.MULTILINE), "").trim()

    private companion object {
        const val POLL_MILLIS = 30_000L

        fun EvolutionRun.dto() = EvolutionRunDto(id, status.name, stage, report, error, startedAt.toString(), finishedAt?.toString())
        fun Proposal.dto() = ProposalDto(id, kind.name, title, summary, changes, previous, status.name, createdAt.toString())
    }
}
