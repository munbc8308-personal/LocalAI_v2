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

    test("보통 글은 그대로 (도구 이름으로 시작해도 꼴이 다르면), 남은 따옴표 표시는 지운다") {
        run(listOf("먼저 폴더를 ", "볼게.")).let { (out, leak) -> out shouldBe "먼저 폴더를 볼게."; leak.call shouldBe null }
        run(listOf("list", " 결과입니다")).first shouldBe "list 결과입니다"
        run(listOf("확인했어.\n<|\"|>\n정리하면")).first shouldBe "확인했어.\n\"\n정리하면"
        run(listOf("unknown_tool{a:1}")).let { (out, leak) -> out shouldBe "unknown_tool{a:1}"; leak.call shouldBe null }
        run(listOf("list_files{엉터리")).let { (out, leak) -> out shouldBe "list_files{엉터리"; leak.call shouldBe null }
    }
})
