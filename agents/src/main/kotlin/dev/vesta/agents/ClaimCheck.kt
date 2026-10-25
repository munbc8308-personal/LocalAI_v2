package dev.vesta.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 작업 주장 확인 (가계부 앱 시험에서): 마지막 답의 "빌드 성공·테스트 통과·파일 수정"을 이번 실행의 도구 기록과 맞춰 본다.
 * 모델이 테스트를 한 번도 성공시키지 않고 "테스트 통과를 확인했다"고 보고한 일이 있었다. 규칙만으로 본다(모델 없이)
 */
internal class ClaimCheck {
    private data class Step(val index: Int, val tool: String, val command: String?, val path: String?, val ok: Boolean, val output: String)

    private val steps = mutableListOf<Step>()

    fun record(tool: String, arguments: JsonObject, ok: Boolean, output: String) {
        fun arg(key: String) = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        steps += Step(steps.size, tool, arg("command"), arg("path") ?: arg("to") ?: arg("destination"), ok, output.takeLast(4_000))
    }

    /** 확인되지 않은 주장 (사람이 읽는 한 줄씩). 없으면 빈 목록 */
    fun unverified(answer: String): List<String> = buildList {
        val lastWrite = steps.lastOrNull { it.tool in WRITES }?.index ?: -1
        fun passed(command: Regex) = steps.filter { s -> s.tool == SHELL && s.command?.let { command.containsMatchIn(it) } == true }
        if (TEST_CLAIM.containsMatchIn(answer)) {
            val runs = passed(TEST_COMMAND)
            val good = runs.lastOrNull { it.ok && !FAILED.containsMatchIn(it.output) }
            when {
                runs.isEmpty() -> add("테스트 통과 — 이번 작업에서 테스트 명령을 실행한 기록이 없음")
                good == null -> add("테스트 통과 — 테스트 명령이 성공한 기록이 없음 (마지막: ${summary(runs.last())})")
                good.index < lastWrite -> add("테스트 통과 — 테스트가 성공한 뒤 파일을 다시 고쳤음")
            }
        }
        if (BUILD_CLAIM.containsMatchIn(answer)) {
            val runs = passed(BUILD_COMMAND)
            val good = runs.lastOrNull { it.ok && !FAILED.containsMatchIn(it.output) }
            when {
                runs.isEmpty() -> add("빌드 성공 — 이번 작업에서 빌드 명령을 실행한 기록이 없음")
                good == null -> add("빌드 성공 — 빌드 명령이 성공한 기록이 없음 (마지막: ${summary(runs.last())})")
                good.index < lastWrite -> add("빌드 성공 — 빌드가 성공한 뒤 파일을 다시 고쳤음")
            }
        }
        // "X.swift를 수정했다"류: 그 파일 이름으로 쓰기·고치기·옮기기가 성공한 기록이 있어야
        val written = steps.filter { it.tool in WRITES && it.ok }.mapNotNull { it.path?.substringAfterLast('/') }.toSet()
        for (sentence in answer.split(Regex("\\n|[.!?](\\s|$)")).filter { EDIT_VERB.containsMatchIn(it) && !FUTURE.containsMatchIn(it) }) {
            FILE.findAll(sentence).map { it.value.substringAfterLast('/') }.distinct()
                .filter { it !in written }
                .forEach { add("$it 수정 — 이번 작업에서 이 파일을 쓴 기록이 없음") }
        }
    }.distinct()

    /** 빌드·테스트가 이번 실행에서 성공했고 그 뒤 파일을 고치지 않았는지 (계획 항목 완료 확인, M32 J-05). 아니면 그 이유 */
    fun verified(check: dev.vesta.core.plan.PlanCheck): String? {
        val command = when (check) {
            dev.vesta.core.plan.PlanCheck.NONE -> return null
            dev.vesta.core.plan.PlanCheck.BUILD -> BUILD_COMMAND
            dev.vesta.core.plan.PlanCheck.TEST -> TEST_COMMAND
        }
        val name = if (check == dev.vesta.core.plan.PlanCheck.TEST) "테스트" else "빌드"
        val lastWrite = steps.lastOrNull { it.tool in WRITES }?.index ?: -1
        val runs = steps.filter { s -> s.tool == SHELL && s.command?.let { command.containsMatchIn(it) } == true }
        val good = runs.lastOrNull { it.ok && !FAILED.containsMatchIn(it.output) }
        return when {
            runs.isEmpty() -> "이번 실행에서 $name 명령을 실행한 기록이 없음"
            good == null -> "$name 명령이 성공한 기록이 없음 (마지막: ${summary(runs.last())})"
            good.index < lastWrite -> "$name 가 성공한 뒤 파일을 다시 고쳤음"
            else -> null
        }
    }

    private fun summary(s: Step) = (if (s.ok) "" else "실패, ") +
        (s.output.lineSequence().firstOrNull { FAILED.containsMatchIn(it) } ?: s.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()).trim().take(100)

    companion object {
        private const val SHELL = "run_shell"
        private val WRITES = setOf("write_file", "apply_patch", "move_file", "make_directory")
        private val TEST_CLAIM = Regex("(테스트|tests?)[^\\n.]{0,25}(통과|성공|passed|succeeded|문제\\s?없|모두\\s?정상)", RegexOption.IGNORE_CASE)
        private val BUILD_CLAIM = Regex("빌드[^\\n.]{0,25}(성공|통과|문제\\s?없|정상)|build succeeded", RegexOption.IGNORE_CASE)
        private val TEST_COMMAND = Regex("\\b(test|pytest|vitest|jest)\\b")
        private val BUILD_COMMAND = Regex("\\b(build|xcodebuild|gradlew?|make|cargo|tsc|swift\\s+build|npm\\s+run)\\b")
        private val FAILED = Regex("\\*\\* (BUILD|TEST) FAILED \\*\\*|\\berror:|\\bFAILED\\b|Parsing project spec failed|failures?=\"[1-9]")
        private val EDIT_VERB = Regex("(수정했|고쳤|작성했|만들었|추가했|생성했|바꿨|변경했|업데이트했|되살렸|복구했)")
        private val FUTURE = Regex("(할게|하겠|할\\s?거|하자|할까|예정|하려)")
        private val FILE = Regex("[\\w./-]+\\.(swift|kt|kts|java|py|js|ts|tsx|jsx|ya?ml|json|md|plist|toml|gradle|sh|html|css|go|rs)\\b")
    }
}
