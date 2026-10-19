package dev.vesta.channels

import dev.vesta.core.AgentType
import dev.vesta.core.RiskLevel
import dev.vesta.core.SourceType
import dev.vesta.core.knowledge.Evidence

/** 메신저 채널이 함께 쓰는 문구 (PROJECT.md 6.5) */
object ChannelText {
    fun agentName(agent: AgentType) = when (agent) {
        AgentType.CHAT -> "일반 대화"
        AgentType.DOCS -> "문서 질의"
        AgentType.WEB -> "웹 리서치"
        AgentType.CODE -> "코드 도우미"
        AgentType.TASK -> "작업 에이전트"
        AgentType.PERSONAL -> "개인 비서"
        AgentType.IMAGE -> "이미지 생성"
    }

    fun riskName(risk: RiskLevel) = when (risk) {
        RiskLevel.READ -> "읽기"
        RiskLevel.NETWORK -> "네트워크"
        RiskLevel.WRITE -> "쓰기"
        RiskLevel.EXEC -> "실행"
    }

    private val citation = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")

    /** 답변에서 실제로 인용한 [n]의 근거만, 번호 순으로 */
    fun citedSources(answer: String, evidence: List<Evidence>): List<Evidence> {
        val cited = citation.findAll(answer).flatMap { m -> m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() } }.toSet()
        return evidence.filter { it.citation != null && it.citation in cited }.distinctBy { it.citation }.sortedBy { it.citation }
    }

    /** 로컬 문서는 파일 이름과 위치만 보여 준다 (폴더 경로는 메신저로 보내지 않음) */
    fun localLabel(evidence: Evidence): String {
        val name = evidence.ref.substringAfterLast('/').ifEmpty { evidence.title }
        return listOfNotNull(name, evidence.location).joinToString(" · ")
    }

    fun isLocal(evidence: Evidence) = evidence.source != SourceType.WEB
}
