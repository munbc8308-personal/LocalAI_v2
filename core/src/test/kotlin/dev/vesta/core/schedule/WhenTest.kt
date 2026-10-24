package dev.vesta.core.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZonedDateTime

private val zone = ZoneId.of("Asia/Seoul")
/** 2026-10-25 일요일 10:20 */
private val now = ZonedDateTime.of(2026, 10, 25, 10, 20, 0, 0, zone)

private fun spec(text: String) = When.parse(text, now)?.spec?.canonical
private fun next(text: String) = When.parse(text, now)?.spec?.next(now)?.toLocalDateTime()?.toString()

class WhenTest : FunSpec({
    test("언제 읽기: 매일·평일·주말·매주·매달·마다·오늘·내일·날짜, 오전·오후·반·분") {
        spec("매일 8시 오늘 날씨 알려 줘") shouldBe "daily 08:00"
        spec("매일 오후 6시 30분에 퇴근 알림") shouldBe "daily 18:30"
        spec("매일 08:05 뉴스") shouldBe "daily 08:05"
        spec("매일 저녁 7시반 운동") shouldBe "daily 19:30"
        spec("매일 밤 12시 정리") shouldBe "daily 00:00"
        spec("평일 아침 7시 일정") shouldBe "weekdays 07:00"
        spec("주말 10시 산책") shouldBe "weekends 10:00"
        spec("매주 월,수 9시 회의 준비") shouldBe "weekly MON,WED 09:00"
        spec("매주 금요일 오후 5시 주간 정리") shouldBe "weekly FRI 17:00"
        spec("매달 1일 9시 가계부") shouldBe "monthly 1 09:00"
        spec("3시간마다 메일 확인") shouldBe "every 180m"
        spec("30분마다 확인") shouldBe "every 30m"
        spec("내일 9시 회의 알려 줘") shouldBe "once 2026-10-26T09:00"
        spec("오늘 오후 3시 전화") shouldBe "once 2026-10-25T15:00"
        spec("10월 30일 14시 발표 준비") shouldBe "once 2026-10-30T14:00"
        spec("1월 2일 9시 새해 계획") shouldBe "once 2027-01-02T09:00"
        When.parse("매일 8시 오늘 날씨 알려 줘", now)!!.task shouldBe "오늘 날씨 알려 줘"
    }

    test("못 읽거나 쓸 수 없는 것") {
        When.parse("날씨 알려 줘", now) shouldBe null
        When.parse("매일 날씨", now) shouldBe null
        When.parse("10분마다 확인", now)!!.problem shouldBe "반복은 30분~24시간 간격만 됩니다"
        When.parse("매일 25시 확인", now)!!.spec shouldBe null
        When.parse("오늘 9시 지난 일", now)!!.problem shouldBe "이미 지난 시각입니다"
        When.parse("2월 30일 9시", now)!!.problem shouldBe "없는 날짜입니다"
        When.parse("매달 32일 9시", now)!!.spec shouldBe null
    }

    test("다음 실행 시각") {
        next("매일 8시 x") shouldBe "2026-10-26T08:00"
        next("매일 11시 x") shouldBe "2026-10-25T11:00"
        next("평일 7시 x") shouldBe "2026-10-26T07:00"
        next("주말 10시 x") shouldBe "2026-10-31T10:00"
        next("매주 일 10시 x") shouldBe "2026-11-01T10:00"
        next("매달 31일 9시 x") shouldBe "2026-10-31T09:00"
        When.Monthly(31, java.time.LocalTime.of(9, 0)).next(ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, zone))!!.toLocalDateTime().toString() shouldBe "2026-12-31T09:00"
        next("3시간마다 x") shouldBe "2026-10-25T12:00"
        next("30분마다 x") shouldBe "2026-10-25T10:30"
    }

    test("저장한 꼴을 다시 읽고 사람 말로") {
        listOf("daily 08:00", "weekdays 07:00", "weekends 10:00", "weekly MON,WED 09:00", "monthly 1 09:00", "every 180m", "once 2026-10-26T09:00")
            .forEach { When.of(it)!!.canonical shouldBe it }
        When.of("weekly MON,WED 09:00")!!.describe() shouldBe "매주 월,수 오전 9시"
        When.of("daily 18:30")!!.describe() shouldBe "매일 오후 6시 30분"
        When.of("every 180m")!!.describe() shouldBe "3시간마다"
        When.of("once 2026-10-26T00:00")!!.describe() shouldBe "10월 26일 오전 12시 한 번"
        When.of("엉터리") shouldBe null
    }
})
