package dev.vesta.core.schedule

import dev.vesta.core.ChannelType
import dev.vesta.core.SessionId
import dev.vesta.core.UserId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 예약 시각 (PROJECT.md S-01, M29). 규칙으로만 읽어 늘 같은 뜻이 된다. [canonical]로 저장하고 [of]로 다시 읽는다.
 * [next]는 [after]보다 뒤의 첫 실행 시각 (없으면 null: 한 번만 하는 예약이 지났을 때)
 */
sealed interface When {
    val canonical: String
    fun next(after: ZonedDateTime): ZonedDateTime?
    fun describe(): String

    /** 같은 시각, 정한 요일만 (매일·평일·주말·매주) */
    data class Days(val days: Set<DayOfWeek>, val time: LocalTime) : When {
        override val canonical get() = when (days) {
            ALL -> "daily ${hm(time)}"
            WEEKDAYS -> "weekdays ${hm(time)}"
            WEEKENDS -> "weekends ${hm(time)}"
            else -> "weekly ${days.sorted().joinToString(",") { it.name.take(3) }} ${hm(time)}"
        }
        override fun next(after: ZonedDateTime): ZonedDateTime? = (0..7L).asSequence()
            .map { after.toLocalDate().plusDays(it).atTime(time).atZone(after.zone) }
            .firstOrNull { it.isAfter(after) && it.dayOfWeek in days }
        override fun describe() = when (days) {
            ALL -> "매일"
            WEEKDAYS -> "평일"
            WEEKENDS -> "주말"
            else -> "매주 " + days.sorted().joinToString(",") { KOREAN_DAYS[it.value - 1].toString() }
        } + " " + korean(time)
    }

    /** 매달 [day]일 (그 달에 없는 날이면 건너뛴다) */
    data class Monthly(val day: Int, val time: LocalTime) : When {
        override val canonical get() = "monthly $day ${hm(time)}"
        override fun next(after: ZonedDateTime): ZonedDateTime? = (0..24L).asSequence()
            .map { after.toLocalDate().withDayOfMonth(1).plusMonths(it) }
            .filter { day <= it.lengthOfMonth() }
            .map { it.withDayOfMonth(day).atTime(time).atZone(after.zone) }
            .firstOrNull { it.isAfter(after) }
        override fun describe() = "매달 ${day}일 ${korean(time)}"
    }

    /** [minutes]분마다 (정각부터 센다: 3시간마다면 0·3·6…시) */
    data class Every(val minutes: Int) : When {
        override val canonical get() = "every ${minutes}m"
        override fun next(after: ZonedDateTime): ZonedDateTime {
            val start = after.truncatedTo(ChronoUnit.DAYS)
            val passed = ChronoUnit.MINUTES.between(start, after)
            return start.plusMinutes((passed / minutes + 1) * minutes)
        }
        override fun describe() = if (minutes % 60 == 0) "${minutes / 60}시간마다" else "${minutes}분마다"
    }

    /** 한 번만 */
    data class Once(val at: LocalDateTime) : When {
        override val canonical get() = "once ${at.truncatedTo(ChronoUnit.MINUTES)}"
        override fun next(after: ZonedDateTime): ZonedDateTime? = at.atZone(after.zone).takeIf { it.isAfter(after) }
        override fun describe() = "${at.monthValue}월 ${at.dayOfMonth}일 ${korean(at.toLocalTime())} 한 번"
    }

    companion object {
        val ALL: Set<DayOfWeek> = DayOfWeek.entries.toSet()
        val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val WEEKENDS: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        /** 반복은 이보다 자주 하지 않는다 (S-05) */
        const val MIN_EVERY = 30
        private const val KOREAN_DAYS = "월화수목금토일"

        private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
        private fun korean(t: LocalTime): String {
            val part = if (t.hour < 12) "오전" else "오후"
            val h = (t.hour % 12).let { if (it == 0) 12 else it }
            return "$part ${h}시" + if (t.minute > 0) " ${t.minute}분" else ""
        }

        /** 저장한 꼴 읽기 */
        fun of(canonical: String): When? = runCatching {
            val p = canonical.trim().split(' ')
            fun time(s: String) = LocalTime.parse(s)
            when (p[0]) {
                "daily" -> Days(ALL, time(p[1]))
                "weekdays" -> Days(WEEKDAYS, time(p[1]))
                "weekends" -> Days(WEEKENDS, time(p[1]))
                "weekly" -> Days(p[1].split(',').map { d -> DayOfWeek.entries.first { it.name.startsWith(d) } }.toSet(), time(p[2]))
                "monthly" -> Monthly(p[1].toInt(), time(p[2]))
                "every" -> Every(p[1].removeSuffix("m").toInt())
                "once" -> Once(LocalDateTime.parse(p[1]))
                else -> null
            }
        }.getOrNull()

        private val TIME = "(?:(오전|오후|아침|낮|저녁|밤|새벽)\\s*)?(?:(\\d{1,2}):(\\d{2})|(\\d{1,2})\\s*시(?:\\s*(반)|\\s*(\\d{1,2})\\s*분)?)"
        private val DAY_NAMES = "[월화수목금토일](?:요일)?"
        private val SPEC = Regex(
            "^\\s*(?:" +
                "(매일|평일|주말)" +
                "|매주\\s*($DAY_NAMES(?:\\s*[,·와과및]?\\s*$DAY_NAMES)*)" +
                "|(?:매달|매월)\\s*(\\d{1,2})\\s*일" +
                "|(오늘|내일|모레)" +
                "|(?:(\\d{4})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일" +
                ")\\s*$TIME\\s*(?:에|마다)?(?:\\s+|$)(.*)$",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val EVERY = Regex("^\\s*(\\d{1,3})\\s*(시간|분)\\s*마다(?:\\s+|$)(.*)$", RegexOption.DOT_MATCHES_ALL)

        /**
         * `<언제> <할 일>`에서 언제를 읽는다 (S-01). [now]는 오늘·내일과 연도 없는 날짜를 정할 때.
         * 언제를 못 읽으면 null. 읽었지만 쓸 수 없는 값(25시, 10분마다, 지난 시각)이면 [Parsed.problem]
         */
        fun parse(text: String, now: ZonedDateTime): Parsed? {
            EVERY.find(text)?.let { m ->
                val n = m.groupValues[1].toInt()
                val minutes = if (m.groupValues[2] == "시간") n * 60 else n
                val task = m.groupValues[3].trim()
                return if (minutes < MIN_EVERY || minutes > 24 * 60) Parsed(null, task, "반복은 ${MIN_EVERY}분~24시간 간격만 됩니다")
                else Parsed(Every(minutes), task)
            }
            val m = SPEC.find(text) ?: return null
            val g = m.groupValues
            val task = g[14].trim()
            val time = time(g[8], g[9], g[10], g[11], g[12], g[13]) ?: return Parsed(null, task, "시각을 읽지 못했습니다 (0~23시, 0~59분)")
            val spec: When = when {
                g[1] == "매일" -> Days(ALL, time)
                g[1] == "평일" -> Days(WEEKDAYS, time)
                g[1] == "주말" -> Days(WEEKENDS, time)
                g[2].isNotEmpty() -> Days(Regex(DAY_NAMES).findAll(g[2]).map { DayOfWeek.of(KOREAN_DAYS.indexOf(it.value[0]) + 1) }.toSet(), time)
                g[3].isNotEmpty() -> g[3].toInt().takeIf { it in 1..31 }?.let { Monthly(it, time) } ?: return Parsed(null, task, "날짜는 1~31일입니다")
                g[4].isNotEmpty() -> Once(now.toLocalDate().plusDays(listOf("오늘", "내일", "모레").indexOf(g[4]).toLong()).atTime(time))
                else -> {
                    val date = runCatching {
                        val explicit = g[5].toIntOrNull()
                        val d = LocalDate.of(explicit ?: now.year, g[6].toInt(), g[7].toInt())
                        if (explicit == null && d.atTime(time).atZone(now.zone).isBefore(now)) d.plusYears(1) else d
                    }.getOrNull() ?: return Parsed(null, task, "없는 날짜입니다")
                    Once(date.atTime(time))
                }
            }
            if (spec is Once && spec.next(now) == null) return Parsed(null, task, "이미 지난 시각입니다")
            return Parsed(spec, task)
        }

        private fun time(part: String, hh: String, mm: String, h: String, half: String, minute: String): LocalTime? {
            var hour = (hh.ifEmpty { h }).toIntOrNull() ?: return null
            val min = when { mm.isNotEmpty() -> mm.toInt(); half.isNotEmpty() -> 30; minute.isNotEmpty() -> minute.toInt(); else -> 0 }
            if (hour > 23 && !(hour == 24 && min == 0) || min > 59) return null
            when (part) {
                "오후", "저녁" -> if (hour in 1..11) hour += 12
                "밤" -> if (hour in 1..11) hour += 12 else if (hour == 12) hour = 0
                "낮" -> if (hour in 1..5) hour += 12
                "오전", "아침", "새벽" -> if (hour == 12) hour = 0
            }
            if (hour == 24) hour = 0
            return LocalTime.of(hour, min)
        }
    }

    /** 읽은 결과: [spec]이 null이면 [problem]이 이유 */
    data class Parsed(val spec: When?, val task: String, val problem: String? = null)
}

/** 예약 하나 (S-01). [channel]은 결과를 보낼 곳(만든 곳), [lastStatus]는 마지막 결과 한 줄 */
data class Schedule(
    val id: Long,
    val user: UserId,
    val task: String,
    val spec: String,
    val channel: ChannelType,
    val enabled: Boolean,
    val nextAt: Instant?,
    val lastAt: Instant?,
    val lastStatus: String?,
    val createdAt: Instant,
) {
    val parsed: When? get() = When.of(spec)
}

interface ScheduleRepository {
    suspend fun add(user: UserId, task: String, spec: String, channel: ChannelType, nextAt: Instant?): Schedule
    suspend fun list(user: UserId): List<Schedule>
    suspend fun get(id: Long): Schedule?
    suspend fun delete(user: UserId, id: Long): Boolean
    suspend fun setEnabled(user: UserId, id: Long, enabled: Boolean, nextAt: Instant?): Boolean
    /** 켜져 있고 [now]까지 때가 된 것, 이른 것부터 */
    suspend fun due(now: Instant): List<Schedule>
    /** 실행을 마치거나 건너뛴 뒤: 다음 시각(없으면 끔)·마지막 결과 */
    suspend fun advance(id: Long, nextAt: Instant?, lastAt: Instant?, lastStatus: String?)
    /** 실행 하나 남기기 (최근 [keep]개만 두려고) */
    suspend fun addRun(id: Long, session: SessionId, at: Instant)
    /** 최근 [keep]개를 넘는 실행 대화를 빼고 그 대화 ID를 돌려준다 */
    suspend fun trimRuns(id: Long, keep: Int): List<SessionId>
}
