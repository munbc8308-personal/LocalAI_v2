package dev.vesta.training

/**
 * 학습 데이터에서 개인정보·비밀값을 지운다 (PROJECT.md L-02).
 * 메일 주소, 전화번호, 카드·계좌처럼 긴 숫자, API 토큰·비밀번호 표기를 "[제거됨]"으로 바꾼다.
 */
object PrivacyFilter {
    private const val MASK = "[제거됨]"
    private val patterns = listOf(
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        // 토큰: sk-…, xoxb-…, ghp_…, AKIA…, 긴 영숫자 덩어리
        Regex("\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}"),
        Regex("\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("(?i)(password|passwd|비밀번호|암호)\\s*[:=]?\\s*\\S+"),
        Regex("\\b[A-Za-z0-9_-]{32,}\\b"),
        // 전화번호 (010-1234-5678, +82 10 1234 5678, 02-123-4567)
        Regex("(?:\\+?82[- ]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}"),
        Regex("\\b0\\d{1,2}[- .]\\d{3,4}[- .]\\d{4}\\b"),
        // 카드·계좌·주민번호처럼 숫자 9자리 이상 (하이픈 포함)
        Regex("\\b\\d(?:[- ]?\\d){8,}\\b"),
    )

    fun clean(text: String): String = patterns.fold(text) { acc, regex -> regex.replace(acc, MASK) }

    /** TSV 한 칸에 들어갈 수 있게 탭·줄바꿈을 공백으로 */
    fun cell(text: String): String = clean(text).replace(Regex("[\\t\\r\\n]+"), " ").trim()
}
