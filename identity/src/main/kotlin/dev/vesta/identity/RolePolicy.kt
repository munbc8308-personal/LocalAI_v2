package dev.vesta.identity

import dev.vesta.core.AgentType
import dev.vesta.core.RiskLevel
import dev.vesta.core.UserRole

/**
 * 역할별 권한 (PROJECT.md 7.12, U-06). 대화 엔진·도구 목록·API가 모두 이 표를 따른다.
 *
 * | 역할 | 에이전트 | 도구 |
 * |---|---|---|
 * | OWNER | 전부 | 전부 (셸은 매번 승인) |
 * | MEMBER | 전부 (개인 비서는 자기 Google만, Apple 앱 없음) | 셸(EXEC) 제외, 파일은 자기 폴더만 |
 * | GUEST | 일반 대화·웹 | 없음 (로컬 문서·도구 없음) |
 */
object RolePolicy {
    fun agents(role: UserRole): Set<AgentType> = when (role) {
        UserRole.OWNER -> AgentType.entries.toSet()
        // 개인 비서: Apple 앱은 Mac 주인 계정의 데이터라 주인만, 멤버는 자기 Google 계정만 (M40). 도구 단계에서 나눈다
        UserRole.MEMBER -> AgentType.entries.toSet()
        UserRole.GUEST -> setOf(AgentType.CHAT, AgentType.WEB)
    }

    /** 이 위험 등급의 도구를 줄 수 있는지 */
    fun allowsRisk(role: UserRole, risk: RiskLevel): Boolean = when (role) {
        UserRole.OWNER -> true
        UserRole.MEMBER -> risk != RiskLevel.EXEC
        UserRole.GUEST -> false
    }

    /** 사용자 관리·모델·학습·서버 설정 */
    fun canManage(role: UserRole): Boolean = role == UserRole.OWNER

    /** 로컬 문서·코드 컬렉션을 검색할 수 있는지 */
    fun usesLocalData(role: UserRole): Boolean = role != UserRole.GUEST
}
