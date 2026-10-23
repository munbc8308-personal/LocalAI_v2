package dev.vesta.training

import dev.vesta.core.training.CodeContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/** 정답이 붙은 판정 재료 한 줄 (학습기 `--judge` JSONL): D6은 [question], D7은 [statement] */
data class JudgeItem(val kind: String, val state: String, val label: Boolean, val question: String? = null, val statement: String? = null, val note: String? = null) {
    fun toJson(): String = buildJsonObject {
        put("q", kind)
        put("state", state)
        question?.let { put("question", it) }
        statement?.let { put("statement", it) }
        put("label", if (label) 1 else 0)
        note?.let { put("note", it) }
    }.toString()
}

/**
 * D6 코드 근거 충분도 재료 (PROJECT.md L-22). 질문마다 실제 저장소를 검색해 결정 모델이 볼 글을 만들고 정답을 붙인다:
 * - 답이 있는 질문(정답 파일이 적힘): 그 파일이 결정 모델이 보는 앞부분에 들어갔을 때만 1 (안 들어갔으면 검색 문제라 뺀다)
 * - 저장소에 없는 내용을 묻는 질문(정답 파일 칸이 없거나 "-"): 0
 * 저장소가 바뀌면 글도 바뀌므로 학습할 때마다 새로 만든다
 */
object CodeSufficiencyData {
    /** TSV 행: 질문<TAB>정답 파일 경로에 들어가는 글자(| 로 여러 개, 없으면 "-"). 한 열이면 없는 질문 */
    fun rows(path: Path): List<Pair<String, List<String>>> = if (!path.exists()) emptyList() else path.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cells = line.split('\t')
            cells[0].trim() to cells.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.split('|')?.map { it.trim() }.orEmpty()
        }

    suspend fun build(rows: List<Pair<String, List<String>>>, context: suspend (String) -> CodeContext?): List<JudgeItem> = rows.mapNotNull { (question, files) ->
        val found = context(question) ?: return@mapNotNull null
        if (files.isEmpty()) return@mapNotNull JudgeItem("D6", found.text, false, question = question, note = "absent")
        val hit = found.files.any { path -> files.any { it in path } }
        if (hit) JudgeItem("D6", found.text, true, question = question, note = files.joinToString("|")) else null
    }

    /** 없으면(재료가 하나도 안 생기면) null */
    fun write(items: List<JudgeItem>, target: Path): Path? {
        if (items.isEmpty()) return null
        Files.writeString(target, items.joinToString("\n") { it.toJson() } + "\n")
        return target
    }
}
