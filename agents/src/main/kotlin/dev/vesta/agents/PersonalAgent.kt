package dev.vesta.agents

import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.DecisionQuestion
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * D11: 질문에 필요한 Apple 앱 그룹 고르기 (PROJECT.md 4.4, 7.7a).
 * Orchard 도구는 52개라 한꺼번에 주면 로컬 모델이 헷갈리고 컨텍스트를 많이 쓴다 → 고른 그룹의 도구만 준다.
 * 결정 모델 확률 상위 그룹(최대 [maxGroups]) + 낱말 규칙으로 보완한다. 시계(clock)는 날짜 계산용으로 늘 넣는다.
 */
class AppGroupSelector(
    private val decision: DecisionClient?,
    private val maxGroups: Int = 3,
    private val minProbability: Double = 0.15,
) {
    suspend fun select(question: String, available: Set<String>): Set<String> {
        val fromKeywords = KEYWORDS.filter { (group, regex) -> group in available && regex.containsMatchIn(question) }.keys
        val fromModel = decision?.let { client ->
            runCatching {
                val criteria = DESCRIPTIONS.filterKeys { it in available }
                val answer = client.decide(question, mapOf("app" to DecisionQuestion.Choice("Which Mac app does the user's request need?", criteria)))["app"]
                (answer as? DecisionAnswer.Choice)?.let { choice ->
                    val ranked = choice.probabilities.entries.sortedByDescending { it.value }
                    (listOf(choice.value) + ranked.filter { it.value >= minProbability }.map { it.key }).distinct().take(maxGroups)
                }
            }.onFailure { log.warn(it) { "앱 그룹 판정 실패, 낱말 규칙만 사용" } }.getOrNull()
        }.orEmpty()
        val chosen = (fromKeywords + fromModel).filter { it in available }.toMutableSet()
        if (chosen.isEmpty()) chosen += available.filter { it in setOf("calendar", "reminder") }
        if ("clock" in available) chosen += "clock"
        return chosen
    }

    companion object {
        val DESCRIPTIONS = mapOf(
            "calendar" to "Calendar: schedule, events, meetings, appointments, free time",
            "reminder" to "Reminders: to-do items, shopping lists, tasks with due dates",
            "mail" to "Mail: read, search, summarize or send email",
            "messages" to "Messages: read or send iMessage/SMS text messages",
            "notes" to "Notes app: read, search or create notes",
            "contacts" to "Contacts: phone numbers, email addresses of people",
            "music" to "Music: play, pause, search songs and playlists",
            "weather" to "Weather forecast, rain, temperature",
            "location" to "Maps: places, addresses, routes, travel time, current location",
            "shortcuts" to "Apple Shortcuts automation",
            "clock" to "Current time, time zones, date calculation",
            // Google (M40): 사용자의 Google 계정
            "gmail" to "Gmail (Google account): search, read, draft or send email",
            "drive" to "Google Drive: find and read files (Google Docs/Sheets/Slides, Word, Excel, PDF), upload new files",
            "sheets" to "Google Sheets: read cells, update, append rows, create spreadsheets",
            "docs" to "Google Docs: create documents or append text",
            "gcal" to "Google Calendar: list or create events",
        )

        private val KEYWORDS = mapOf(
            "calendar" to Regex("일정|약속|회의|캘린더|미팅|스케줄|비어 ?있"),
            "reminder" to Regex("미리 ?알림|할 ?일|리마인더|장보기|todo", RegexOption.IGNORE_CASE),
            "mail" to Regex("메일|이메일|mail", RegexOption.IGNORE_CASE),
            "messages" to Regex("문자|메시지|iMessage|카톡 말고", RegexOption.IGNORE_CASE),
            "notes" to Regex("메모"),
            "contacts" to Regex("연락처|전화번호|번호 ?알려"),
            "music" to Regex("음악|노래|플레이리스트|틀어"),
            "weather" to Regex("날씨|비 ?와|우산|기온"),
            "location" to Regex("길찾기|경로|가는 길|얼마나 걸려|위치|주소|근처"),
            "shortcuts" to Regex("단축어|shortcut", RegexOption.IGNORE_CASE),
            "gmail" to Regex("메일|이메일|지메일|gmail|편지함|답장", RegexOption.IGNORE_CASE),
            "drive" to Regex("드라이브|drive|파일|문서|시트|스프레드|엑셀|excel|워드|word|docx|xlsx|pdf|슬라이드", RegexOption.IGNORE_CASE),
            "sheets" to Regex("시트|스프레드|엑셀|excel|표에|가계부|줄 ?추가", RegexOption.IGNORE_CASE),
            "docs" to Regex("구글 ?문서|google docs|문서(를|로)? ?(만들|작성|써|추가|덧붙)|워드", RegexOption.IGNORE_CASE),
            "gcal" to Regex("일정|약속|회의|캘린더|미팅|스케줄|구글 ?캘린더"),
        )
    }
}
