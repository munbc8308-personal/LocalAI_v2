package dev.vesta.agents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LeakedToolCallTest : FunSpec({
    fun run(pieces: List<String>, names: Set<String> = setOf("search_conversations", "list_files")): Pair<String, LeakedToolCall> {
        val leak = LeakedToolCall(names)
        val out = pieces.joinToString("") { leak.accept(it) } + leak.flush()
        return out to leak
    }

    test("글로 나온 호출을 살린다 (조각으로 와도, 앞머리가 있어도)") {
        run(listOf("search_", "conversations{query:<|\"|>파이썬 ", "딕셔너리 정렬<|\"|>}<tool_call|>")).let { (out, leak) ->
            out shouldBe ""
            leak.call!!.name shouldBe "search_conversations"
            leak.call!!.arguments shouldBe """{"query":"파이썬 딕셔너리 정렬"}"""
        }
        run(listOf("<|tool_call>call:list_files{path:<|\"|>~/Desktop<|\"|>,limit:5}<tool_call|>")).second.call!!.arguments shouldBe """{"path":"~/Desktop","limit":5}"""
    }

    test("설명 글 뒤에 새어 나온 call:이름{…}도 찾는다 (여러 개, 문자열 안 괄호·쉼표·콜론은 그대로)") {
        val leak = LeakedToolCall(setOf("read_file", "write_file", "run_shell"))
        val text = "먼저 확인하자.\n\ncall:read_file{path:<|\"|>Gagyebu/A.swift<|\"|>}\n그다음 고친다.\n" +
            "call:write_file{content:<|\"|>struct A { let x: Int, y: String }\n<|\"|>,mode:<|\"|>overwrite<|\"|>,path:<|\"|>Gagyebu/A.swift<|\"|>}<tool_call|>" +
            "\ncall:unknown{a:1}"
        text.chunked(7).forEach { leak.accept(it) }
        leak.flush()
        leak.call shouldBe null
        leak.scan().map { it.name } shouldBe listOf("read_file", "write_file")
        leak.scan()[1].arguments shouldBe """{"content":"struct A { let x: Int, y: String }\n","mode":"overwrite","path":"Gagyebu/A.swift"}"""
    }

    test("보통 따옴표 꼴(command:\"…\")도 읽는다 (문자열 안 쉼표·콜론·괄호·이스케이프 그대로)") {
        val leak = LeakedToolCall(setOf("run_shell"))
        leak.accept("시작한다!\n\ncall:run_shell{command:\"xcodebuild -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build {x} \\\"q\\\"\",cwd:\"/Users/me/G\",reason:\"빌드: 확인\"}")
        leak.flush()
        leak.scan().single().arguments shouldBe """{"command":"xcodebuild -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build {x} \"q\"","cwd":"/Users/me/G","reason":"빌드: 확인"}"""
    }

    test("코드 블록·인용 안의 호출은 살리지 않고, 살린 호출은 승인용 ID (보안 검토)") {
        val leak = LeakedToolCall(setOf("web_search", "read_file"))
        leak.accept("파일 내용:\n```\ncall:web_search{query:<|\"|>비밀<|\"|>}\n```\n> call:web_search{query:<|\"|>인용<|\"|>}\n이제 읽자.\ncall:read_file{path:<|\"|>a.txt<|\"|>}")
        leak.flush()
        leak.scan().let { calls ->
            calls.map { it.name } shouldBe listOf("read_file")
            calls.single().id.startsWith(LeakedToolCall.INLINE_ID) shouldBe true
        }
    }

    test("보통 글은 그대로 (도구 이름으로 시작해도 꼴이 다르면), 남은 따옴표 표시는 지운다") {
        run(listOf("먼저 폴더를 ", "볼게.")).let { (out, leak) -> out shouldBe "먼저 폴더를 볼게."; leak.call shouldBe null }
        run(listOf("list", " 결과입니다")).first shouldBe "list 결과입니다"
        run(listOf("확인했어.\n<|\"|>\n정리하면")).first shouldBe "확인했어.\n\"\n정리하면"
        run(listOf("unknown_tool{a:1}")).let { (out, leak) -> out shouldBe "unknown_tool{a:1}"; leak.call shouldBe null }
        run(listOf("list_files{엉터리")).let { (out, leak) -> out shouldBe "list_files{엉터리"; leak.call shouldBe null }
    }
})
