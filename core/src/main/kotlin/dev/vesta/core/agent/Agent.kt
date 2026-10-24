package dev.vesta.core.agent

import dev.vesta.core.AgentType
import dev.vesta.core.UserContext
import dev.vesta.core.inference.ChatMessage
import dev.vesta.core.session.Session
import kotlinx.coroutines.flow.Flow

/** 에이전트. 라우터가 고른 에이전트가 요청 하나를 처리하고 이벤트를 흘려보낸다 (PROJECT.md 4.8). */
interface Agent {
    val type: AgentType
    fun handle(request: AgentRequest): Flow<AgentEvent>
}

data class AgentRequest(
    val user: UserContext,
    val session: Session,
    /** 이번 사용자 입력까지 포함한 대화 기록 (시간 순). 마지막은 라우팅 명령을 뗀 질문. */
    val history: List<ChatMessage>,
    /** 문서 검색 대상 컬렉션 이름. 비어 있으면 사용자의 모든 컬렉션. */
    val collections: List<String> = emptyList(),
    /** 라우터 판정 (D2·D3 확률 등). 에이전트가 정보원을 고를 때 참고한다. */
    val routing: dev.vesta.core.decision.RoutingDecision? = null,
    /** CLI를 실행한 폴더와 그 프로젝트 지침 (M17). Mac 주인일 때만 들어온다 */
    val workspace: Workspace? = null,
    /** 소리로 들려줄 답 (음성 대화, M19): 짧게 말하듯이 */
    val spoken: Boolean = false,
    /** 사용자별 설정의 호칭·말투 (U-07, M20) */
    val persona: String? = null,
    /**
     * 이미 로컬 데이터를 읽은 흐름에서 온 요청 (팀 나눠 맡기기, M22). 리더가 로컬 데이터를 읽은 뒤 맡긴 할 일에는 그 내용이 들어 있을 수 있어,
     * 맡은 에이전트도 처음부터 인터넷 도구에 승인을 받는다
     */
    val tainted: Boolean = false,
    /** 사람이 없는 실행 (예약 작업, M29 S-05): 승인이 필요한 도구(쓰기·셸·보내기)를 주지 않는다 */
    val unattended: Boolean = false,
)

/** 사용자 입력. [images]는 이번 질문에 붙인 이미지 (V-04), [workspace]는 CLI 작업 폴더 (M17) */
data class UserInput(
    val text: String,
    val images: List<dev.vesta.core.media.ImageInput> = emptyList(),
    val workspace: Workspace? = null,
    /** 답을 소리로 들려준다 (Discord 음성 채널·웹 음성 대화, M19) */
    val spoken: Boolean = false,
    /** 사람이 없는 실행 (예약 작업, M29) */
    val unattended: Boolean = false,
)

/**
 * CLI 작업 폴더 (PROJECT.md 7.14, M17). `vesta chat`을 실행한 폴더를 파일 도구·셸의 기준으로 쓰고(상대 경로는 이 폴더 기준),
 * 그 폴더의 `VESTA.md`([instructions])를 프로젝트 지침으로 시스템 프롬프트에 붙인다
 */
data class Workspace(
    val path: String,
    val instructions: String? = null,
    /** 작업 폴더의 스킬(`.vesta/skills`, K-01). CLI가 VESTA.md처럼 사용자 승인(해시)을 받은 것만 넣는다 */
    val skills: List<dev.vesta.core.skill.Skill> = emptyList(),
) {
    companion object {
        /** 지침 최대 글자 수 (넘으면 앞부분만) */
        const val MAX_INSTRUCTIONS = 8_000

        /**
         * 작업 폴더로 쓸 수 있는가: 홈 아래의 실제 폴더이고 홈 자체는 아니다
         * (홈 전체를 파일 도구에 열지 않게. 비밀 파일·보호 폴더는 PathSandbox가 따로 막는다)
         */
        fun usable(path: String, home: String = System.getProperty("user.home")): Boolean {
            val dir = runCatching { java.nio.file.Path.of(path).toRealPath() }.getOrNull() ?: return false
            val homeDir = runCatching { java.nio.file.Path.of(home).toRealPath() }.getOrNull() ?: return false
            return java.nio.file.Files.isDirectory(dir) && dir.startsWith(homeDir) && dir != homeDir
        }
    }
}
