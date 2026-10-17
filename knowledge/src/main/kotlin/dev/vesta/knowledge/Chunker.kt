package dev.vesta.knowledge

/** 검색 단위 청크. [location]은 사람이 읽는 위치 ("12–30행", "3–4쪽"). */
data class DocumentChunk(val text: String, val heading: String?, val location: String?)

/**
 * 문단을 모아 목표 크기의 청크를 만든다 (PROJECT.md K-04).
 * - 제목 경로가 바뀌면 새 청크를 시작한다.
 * - 이전 청크의 마지막 문단을 겹쳐 넣어 문맥이 끊기지 않게 한다.
 * - 목표보다 긴 문단은 문장·글자 단위로 자른다.
 */
class Chunker(
    private val targetChars: Int = 1_200,
    private val overlapChars: Int = 200,
) {
    fun chunk(document: ParsedDocument): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        val current = mutableListOf<TextBlock>()
        var size = 0
        var hasNew = false // 겹침으로 넣은 문단 말고 새 문단이 있는지

        fun emit() {
            if (!hasNew) return
            chunks += DocumentChunk(current.joinToString("\n\n") { it.text }, current.first().heading, location(current))
            // 겹침: 마지막 문단이 짧으면 다음 청크 앞에 다시 넣는다
            val last = current.last()
            current.clear()
            size = 0
            hasNew = false
            if (last.text.length <= overlapChars) {
                current += last
                size = last.text.length
            }
        }

        for (block in document.blocks.flatMap { split(it) }) {
            if (current.isNotEmpty() && current.last().heading != block.heading) {
                emit()
                current.clear() // 다른 구역의 문단은 겹치지 않는다
                size = 0
            }
            if (hasNew && size + block.text.length > targetChars) emit()
            current += block
            size += block.text.length
            hasNew = true
        }
        emit()
        return chunks
    }

    private fun split(block: TextBlock): List<TextBlock> {
        if (block.text.length <= targetChars) return listOf(block)
        val pieces = mutableListOf<String>()
        val sentence = StringBuilder()
        for (part in block.text.split(Regex("(?<=[.!?。\\n])\\s+"))) {
            if (sentence.isNotEmpty() && sentence.length + part.length > targetChars) {
                pieces += sentence.toString()
                sentence.clear()
            }
            if (part.length > targetChars) pieces += part.chunked(targetChars) else sentence.append(part).append(' ')
        }
        if (sentence.isNotBlank()) pieces += sentence.toString().trim()
        return pieces.map { block.copy(text = it.trim()) }
    }

    private fun location(blocks: List<TextBlock>): String? {
        val pages = blocks.mapNotNull { it.page }
        if (pages.isNotEmpty()) return if (pages.min() == pages.max()) "${pages.min()}쪽" else "${pages.min()}–${pages.max()}쪽"
        val start = blocks.mapNotNull { it.startLine }.minOrNull() ?: return null
        val end = blocks.mapNotNull { it.endLine }.maxOrNull() ?: start
        return if (start == end) "${start}행" else "$start–${end}행"
    }
}
