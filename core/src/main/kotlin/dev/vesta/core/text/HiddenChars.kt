package dev.vesta.core.text

/**
 * 화면에 그대로 보이지 않는 문자 (M17 VESTA.md 신뢰, M22 스킬): 제어 문자(줄바꿈·탭 제외), 서식 문자(양방향 제어·폭 없는 문자·태그 블록 U+E0000~),
 * 사용자 정의·미할당·서로게이트, 줄·문단 구분자, 기본 무시 문자. 들어 있으면 승인 화면과 모델이 받는 내용이 달라 보일 수 있다.
 * UTF-16 단위가 아니라 코드 포인트로 본다
 */
object HiddenChars {
    fun has(text: String): Boolean = marks(text).any { it }

    /** 보이지 않는 문자를 `\u{…}`로 */
    fun visible(text: String): String = buildString {
        val points = text.codePoints().toArray()
        val flags = marks(text)
        points.forEachIndexed { i, cp -> if (flags[i]) append("\\u{%x}".format(cp)) else appendCodePoint(cp) }
    }

    /** 코드 포인트마다 숨은 문자인가. 기호 뒤의 표시 선택자(U+FE0E·FE0F, "❤️")는 정상 이모지 표기라 허용한다 */
    private fun marks(text: String): List<Boolean> {
        val points = text.codePoints().toArray()
        return points.mapIndexed { i, cp ->
            if ((cp == 0xFE0E || cp == 0xFE0F) && i > 0 && Character.getType(points[i - 1]).toByte() == Character.OTHER_SYMBOL) false else hidden(cp)
        }
    }

    /** 기본 무시 문자(Default_Ignorable)·보이지 않는 채움 문자: 결합 무시, 한글 채움, 몽골 변형 선택자, 점자 빈칸, 변형 선택자 */
    private val IGNORABLE = listOf(
        0x034F..0x034F, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F, 0x2800..0x2800, 0x3164..0x3164,
        0xFE00..0xFE0F, 0xFFA0..0xFFA0, 0xE0100..0xE01EF,
    )

    private fun hidden(cp: Int): Boolean {
        if (cp == '\n'.code || cp == '\t'.code) return false
        if (IGNORABLE.any { cp in it }) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> cp in 0xE0000..0xE007F
        }
    }
}
