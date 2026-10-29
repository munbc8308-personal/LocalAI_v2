package dev.vesta.decision

import dev.vesta.core.AgentType
import dev.vesta.core.decision.DecisionQuestion

/**
 * 라우팅 판정 질문 D1~D3 (PROJECT.md 4.4).
 * M4 평가(eval/routing-ko.tsv): Laya는 영어 문구가 한국어 문구보다 정확했다 (37% vs 31%). 메인 모델 판정에도 같은 설명을 쓴다.
 */
object RoutingQuestions {
    const val AGENT = "agent"
    const val NEEDS_WEB = "needsWeb"
    const val NEEDS_LOCAL = "needsLocal"

    val descriptionsEn: Map<AgentType, String> = mapOf(
        AgentType.CHAT to "General conversation, explanations of well-known facts or concepts, writing, translation, advice. No tools, no documents, no internet.",
        AgentType.DOCS to "Answer from the user's own saved documents, notes, meeting minutes, manuals or PDFs that were registered earlier.",
        AgentType.WEB to "Needs current or recent information from the internet: news, prices, exchange rates, stock, sports results, latest versions, releases.",
        AgentType.CODE to "Programming help: write, explain, debug or review code, error messages, SQL, git, build tools.",
        AgentType.TASK to "Perform actions on files or the computer: organize, move, convert or save files, run commands or builds, schedule jobs.",
        AgentType.PERSONAL to "Personal assistant on the user's Mac apps and Google account: calendar, schedule, reminders, to-dos, mail, Gmail, Google Drive files, Google Sheets, Google Docs, text messages, contacts, notes app, weather, music.",
        AgentType.IMAGE to "Create or draw a new image, picture, illustration, logo or artwork.",
    )

    val descriptionsKo: Map<AgentType, String> = mapOf(
        AgentType.CHAT to "일반 대화, 잘 알려진 사실·개념 설명, 글쓰기, 번역, 조언. 도구·문서·인터넷 불필요.",
        AgentType.DOCS to "사용자가 미리 등록한 자기 문서·노트·회의록·매뉴얼·PDF를 근거로 답하기.",
        AgentType.WEB to "인터넷의 최신 정보가 필요: 뉴스, 가격, 환율, 주가, 경기 결과, 최신 버전, 출시 소식.",
        AgentType.CODE to "프로그래밍 도움: 코드 작성·설명·디버깅·리뷰, 오류 메시지, SQL, git, 빌드 도구.",
        AgentType.TASK to "파일이나 컴퓨터에 작업 수행: 파일 정리·이동·변환·저장, 명령·빌드 실행, 예약 작업.",
        AgentType.PERSONAL to "Mac 앱·Google 계정 개인 비서: 캘린더 일정, 미리 알림, 할 일, 메일·Gmail, 구글 드라이브 파일, 구글 시트, 구글 문서, 문자, 연락처, 메모 앱, 날씨, 음악.",
        AgentType.IMAGE to "새 이미지·그림·일러스트·로고 생성.",
    )

    fun questions(language: String): Map<String, DecisionQuestion> {
        val en = language != "ko"
        val descriptions = if (en) descriptionsEn else descriptionsKo
        return mapOf(
            AGENT to DecisionQuestion.Choice(
                if (en) "Which assistant should handle the user's request?" else "사용자 요청을 어느 담당자가 처리해야 하나?",
                descriptions.mapKeys { it.key.name },
            ),
            NEEDS_WEB to DecisionQuestion.Noul(
                if (en) "Does answering require current information from the internet?" else "답하려면 인터넷의 최신 정보가 필요한가?",
            ),
            NEEDS_LOCAL to DecisionQuestion.Noul(
                if (en) "Does answering require the user's own saved documents or code repository?" else "답하려면 사용자가 저장한 자기 문서나 코드 저장소가 필요한가?",
            ),
        )
    }
}
