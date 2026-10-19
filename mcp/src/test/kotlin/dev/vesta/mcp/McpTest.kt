package dev.vesta.mcp

import dev.vesta.core.RiskLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class McpTest : FunSpec({
    fun info(name: String, readOnly: Boolean?) = McpToolInfo(name, name, buildJsonObject { put("type", "object") }, readOnly, null, null)

    test("위험 등급: 설정 > 보내기·단축어 EXEC 고정 > readOnlyHint > 기본 WRITE") {
        RiskMapping.of(info("calendar_info", true), emptyMap()) shouldBe RiskLevel.READ
        RiskMapping.of(info("reminder_create", false), emptyMap()) shouldBe RiskLevel.WRITE
        RiskMapping.of(info("notes_open", null), emptyMap()) shouldBe RiskLevel.WRITE
        RiskMapping.of(info("mail_send", true), emptyMap()) shouldBe RiskLevel.EXEC // 서버가 읽기 전용이라 해도 EXEC
        RiskMapping.of(info("music_control", false), mapOf("music_control" to "READ")) shouldBe RiskLevel.READ
    }

    test("메일 보내기 승인 화면에 받는 사람·제목·본문이 다 나온다") {
        val tool = McpTool("orchard", info("mail_send", false), RiskLevel.EXEC, McpStdioClient("x", listOf("true")))
        val details = tool.describe(buildJsonObject {
            put("to", JsonArray(listOf(JsonPrimitive("mom@example.com"))))
            put("subject", "늦어요")
            put("content", "저녁 늦어요")
        })
        details.title shouldContain "mom@example.com"
        details.title shouldContain "늦어요"
        details.body shouldContain "content: 저녁 늦어요"
        details.warning!! shouldContain "실제로 보내집니다"
        tool.group shouldBe "mail"
    }

    test("stdio JSON-RPC: 가짜 MCP 서버로 도구 목록·호출") {
        // 줄마다 JSON-RPC 요청을 읽어 정해진 답을 돌려주는 작은 셸 스크립트
        val script = createTempFile("fake-mcp", ".py").apply {
            writeText(
                """
                import sys, json
                for line in sys.stdin:
                    m = json.loads(line)
                    if "id" not in m: continue
                    if m["method"] == "initialize": r = {"protocolVersion": "2025-06-18", "capabilities": {}}
                    elif m["method"] == "tools/list": r = {"tools": [{"name": "clock_time", "description": "time", "inputSchema": {"type": "object"}, "annotations": {"readOnlyHint": True}}]}
                    elif m["method"] == "tools/call": r = {"content": [{"type": "text", "text": "12:00 " + m["params"]["arguments"]["tz"]}]}
                    else: r = {}
                    print(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": r}), flush=True)
                """.trimIndent(),
            )
        }
        McpStdioClient("fake", listOf("python3", "-I", script.toString())).use { client ->
            val tools = client.listTools()
            tools.single().name shouldBe "clock_time"
            tools.single().readOnlyHint shouldBe true
            client.callTool("clock_time", buildJsonObject { put("tz", "Asia/Seoul") }).text shouldBe "12:00 Asia/Seoul"
        }
    }
})
