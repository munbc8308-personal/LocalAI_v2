package dev.vesta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.int
import dev.vesta.api.ApiJson
import dev.vesta.api.ScheduleDto
import dev.vesta.api.ScheduleEditDto
import dev.vesta.api.ScheduleNewDto
import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer

/** 예약 작업 (PROJECT.md S-04, M29). 여기서 만든 예약은 결과가 웹 대화 목록에 남는다 (메신저로 받으려면 메신저에서 /schedule) */
class ScheduleCommand : CliktCommand(name = "schedule") {
    override fun help(context: Context) = "예약 작업: 보기·만들기·멈춤·지금 실행·지우기 (대화에서는 /schedule …, /schedules)"
    override fun run() = Unit

    companion object {
        fun create() = ScheduleCommand().subcommands(
            ScheduleListCommand(), ScheduleAddCommand(), ScheduleToggleCommand("pause", false), ScheduleToggleCommand("resume", true),
            ScheduleRunCommand(), ScheduleRemoveCommand(),
        )
    }
}

private fun CliktCommand.schedules(): List<ScheduleDto> = ApiJson.decodeFromString(ListSerializer(ScheduleDto.serializer()), memoryApi(HttpMethod.Get, "/api/schedules"))

private fun CliktCommand.schedule(number: Int): ScheduleDto? = schedules().getOrNull(number - 1) ?: run { echo("$number 번 예약은 없습니다", err = true); null }

class ScheduleListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "예약 목록"
    override fun run() {
        val all = schedules()
        if (all.isEmpty()) { echo("예약이 없습니다. 예: vesta schedule add 매일 8시 오늘 날씨 알려 줘"); return }
        all.forEach { s ->
            val state = if (!s.enabled) "멈춤" else s.nextAt?.let { "다음 ${java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ')}" } ?: "끝남"
            echo("${s.number.toString().padStart(3)}. ${s.whenText} · ${s.task}  [$state${s.lastStatus?.let { " · 마지막: $it" }.orEmpty()}]")
        }
    }
}

class ScheduleAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "예약 만들기 (예: vesta schedule add 평일 7시 오늘 일정 알려 줘)"
    private val words by argument().multiple(required = true)
    override fun run() {
        memoryApi(HttpMethod.Post, "/api/schedules", ApiJson.encodeToString(ScheduleNewDto.serializer(), ScheduleNewDto(words.joinToString(" "))))
        schedules().lastOrNull()?.let { echo("예약했습니다: ${it.number}. ${it.whenText} · ${it.task}") }
    }
}

class ScheduleToggleCommand(name: String, private val on: Boolean) : CliktCommand(name = name) {
    override fun help(context: Context) = if (on) "멈춘 예약 다시 켜기 (list의 번호)" else "예약 멈추기 (list의 번호)"
    private val number by argument().int()
    override fun run() {
        val s = schedule(number) ?: return
        memoryApi(HttpMethod.Put, "/api/schedules/${s.id}", ApiJson.encodeToString(ScheduleEditDto.serializer(), ScheduleEditDto(on)))
        echo(if (on) "다시 켰습니다: ${s.task}" else "멈췄습니다: ${s.task}")
    }
}

class ScheduleRunCommand : CliktCommand(name = "run") {
    override fun help(context: Context) = "예약을 지금 한 번 돌린다 (결과는 웹 대화 목록 ⏰)"
    private val number by argument().int()
    override fun run() {
        val s = schedule(number) ?: return
        memoryApi(HttpMethod.Post, "/api/schedules/${s.id}/run", "{}")
        echo("돌리기 시작했습니다: ${s.task}")
    }
}

class ScheduleRemoveCommand : CliktCommand(name = "rm") {
    override fun help(context: Context) = "예약 지우기 (list의 번호)"
    private val number by argument().int()
    override fun run() {
        val s = schedule(number) ?: return
        memoryApi(HttpMethod.Delete, "/api/schedules/${s.id}")
        echo("지웠습니다: ${s.task}")
    }
}
