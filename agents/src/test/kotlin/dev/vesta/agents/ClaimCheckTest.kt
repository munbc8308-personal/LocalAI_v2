package dev.vesta.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ClaimCheckTest : FunSpec({
    fun shell(command: String) = buildJsonObject { put("command", JsonPrimitive(command)) }
    fun write(path: String) = buildJsonObject { put("path", JsonPrimitive(path)) }
    val build = "xcodebuild -scheme Gagyebu build"
    val test = "xcodebuild -scheme Gagyebu test"

    test("가계부 시험 그대로: 테스트를 실행하지 않았는데 통과했다고 하면 걸린다") {
        val c = ClaimCheck()
        c.record("write_file", write("Gagyebu/Views/AddTransactionView.swift"), true, "저장함")
        c.record("run_shell", shell("/opt/homebrew/bin/xcodegen generate"), false, "Parsing project spec failed: Decoding failed")
        c.record("run_shell", shell(build), true, "** BUILD SUCCEEDED **\n[종료 코드 0]")
        c.unverified("빌드 성공. `xcodebuild test`를 통해 모든 단위 테스트가 정상적으로 통과됨을 확인.").single() shouldContain "테스트 명령을 실행한 기록이 없음"
    }

    test("테스트가 실패했거나, 성공 뒤 파일을 고쳤으면 걸리고, 마지막에 성공했으면 통과") {
        val c = ClaimCheck()
        c.record("run_shell", shell(test), false, "error: Scheme Gagyebu is not currently configured for the test action.")
        c.unverified("테스트 모두 통과했어").single() shouldContain "성공한 기록이 없음"
        c.record("run_shell", shell(test), true, "** TEST SUCCEEDED **")
        c.unverified("테스트 모두 통과했어").shouldBeEmpty()
        c.record("write_file", write("A.swift"), true, "저장함")
        c.unverified("테스트 모두 통과했어").single() shouldContain "성공한 뒤 파일을 다시 고쳤음"
    }

    test("빌드 성공 주장: 빌드 출력에 오류가 있으면 걸린다") {
        val c = ClaimCheck()
        c.record("run_shell", shell(build), true, "A.swift:3: error: cannot find 'x'\n** BUILD FAILED **")
        c.unverified("빌드가 성공했어").single() shouldContain "error: cannot find 'x'"
    }

    test("도구를 글로만 부른 척하면 걸린다 (RC-07 Windows 시험 그대로), 실제로 불렀거나 앞으로 할 일이면 통과") {
        val answer = """
            이제 **진짜로 `write_file`을 호출할게.** 이 명령이 실행되면 파일의 수정 시간이 바뀔 거야.

            (실제로는 `write_file` 도구를 호출함)

            1. **`network-monitor/static/index.html` 수정 완료**: `write_file` 도구를 사용하여 실제로 파일을 덮어썼어.
        """.trimIndent()
        val c = ClaimCheck()
        c.record("read_file", write("network-monitor/src/main.rs"), true, "fn main")
        c.unverified(answer).let { claims ->
            claims.any { it.startsWith("write_file 호출") } shouldBe true
            claims.any { it.startsWith("index.html 수정") } shouldBe true
        }
        ClaimCheck().unverified("(실제 도구 호출은 시스템이 처리함)").single() shouldContain "실행한 도구가 없음"
        // 실제로 썼으면 통과
        val done = ClaimCheck()
        done.record("write_file", write("network-monitor/static/index.html"), true, "저장함")
        done.unverified("`write_file`을 사용해서 index.html 수정 완료했어").shouldBeEmpty()
        // 앞으로 할 일은 주장이 아님
        ClaimCheck().unverified("이제 `write_file`을 호출해서 고칠게").shouldBeEmpty()
    }

    test("파일 수정 주장: 쓴 기록이 있는 파일만, 앞으로 할 일은 주장이 아님") {
        val c = ClaimCheck()
        c.record("write_file", write("Gagyebu/Views/AddTransactionView.swift"), true, "저장함")
        c.unverified("AddTransactionView.swift를 수정했고 TransactionListView.swift도 고쳤어.").let {
            it.single() shouldBe "TransactionListView.swift 수정 — 이번 작업에서 이 파일을 쓴 기록이 없음"
        }
        c.unverified("이제 TransactionListView.swift를 수정할게").shouldBeEmpty()
        c.unverified("project.yml을 읽어 봤어").shouldBeEmpty()
    }
    test("Python 테스트 실행도 테스트로 알아본다 (팀 검증, M36)") {
        listOf("python3 test_fizzbuzz.py", "python3 -m unittest -q test_textstats.py", "pytest -q").forEach { command ->
            val c = ClaimCheck()
            c.record("run_shell", shell(command), true, "Ran 2 tests in 0.001s\n\nOK\n[종료 코드 0]")
            c.unverified("테스트도 모두 통과했어") shouldBe emptyList()
        }
        val failed = ClaimCheck()
        failed.record("run_shell", shell("python3 -m unittest -q"), true, "FAILED (failures=1)\n[종료 코드 0]")
        failed.unverified("테스트 통과했어").single() shouldContain "성공한 기록이 없음"
    }

    test("파일 이름 없이 저장했다고 하거나, 파일 옆에 '(조사 완료)'만 붙여도 쓴 기록을 본다 (디자인팀 시험)") {
        val researcher = ClaimCheck()
        researcher.record("web_search", buildJsonObject { put("query", JsonPrimitive("food app trends")) }, true, "8건")
        researcher.record("make_directory", write("~/Documents/vesta-design-test"), true, "만듦")
        researcher.unverified("최신 음식 주문 앱 트렌드를 조사해서 요청한 경로에 저장했어.").single() shouldContain "파일을 쓴 기록이 없음"
        researcher.unverified("조사 결과를 저장할게").shouldBeEmpty()
        val lead = ClaimCheck()
        lead.record("write_file", write("~/Documents/vesta-design-test/index.html"), true, "저장함")
        lead.unverified("* `~/Documents/vesta-design-test/research_report.md` (조사 완료)\n* `index.html` (업데이트)").single() shouldContain "research_report.md"
        val saved = ClaimCheck()
        saved.record("write_file", write("research.md"), true, "저장함")
        saved.unverified("요청한 경로에 저장했어").shouldBeEmpty()
    }
})
