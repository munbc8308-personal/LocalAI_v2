package dev.vesta.channels

/**
 * 메신저 길이 제한에 맞춰 답변을 나눈다 (PROJECT.md 6.5 긴 메시지).
 * 빈 줄(문단 경계)에서 자르는 것을 우선하고, 코드 블록 중간에서 잘라야 하면 앞 조각에서 닫고 다음 조각에서 같은 언어로 다시 연다.
 */
object MessageSplitter {
    fun split(text: String, limit: Int): List<String> {
        require(limit >= 200) { "limit too small: $limit" }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        // 지금 열려 있는 코드 블록의 여는 줄 (예: "```kotlin")
        var openFence: String? = null
        // current 안에서 코드 블록 밖 빈 줄이 끝나는 위치 (여기서 자르면 자연스럽다)
        var lastBreak = -1
        val reserve = 4 // 닫는 "\n```"

        fun emit(chunk: String) {
            val trimmed = chunk.trim('\n')
            if (trimmed.isNotBlank()) out += trimmed
        }

        for (line in text.replace("\r\n", "\n").split('\n')) {
            for (piece in line.chunked(limit - reserve - 16).ifEmpty { listOf("") }) {
                while (current.isNotEmpty() && current.length + 1 + piece.length + (if (openFence != null) reserve else 0) > limit) {
                    if (lastBreak > current.length / 2) {
                        // 문단 경계에서 자른다. 경계는 코드 블록 밖이므로 남은 부분의 블록 상태는 그대로다
                        emit(current.substring(0, lastBreak))
                        val rest = current.substring(lastBreak).trimStart('\n')
                        current.setLength(0)
                        current.append(rest)
                        lastBreak = -1
                    } else {
                        val fence = openFence
                        if (fence != null) current.append("\n```")
                        emit(current.toString())
                        current.setLength(0)
                        lastBreak = -1
                        if (fence != null) current.append(fence)
                    }
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(piece)
                val trimmed = piece.trim()
                if (trimmed.startsWith("```")) openFence = if (openFence == null) trimmed else null
                if (trimmed.isEmpty() && openFence == null) lastBreak = current.length
            }
        }
        emit(current.toString())
        return out
    }
}
