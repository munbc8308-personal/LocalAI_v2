package dev.vesta.training

import dev.vesta.core.training.CodeContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class JudgeDataTest : FunSpec({
    test("코드 질문 TSV: 정답 파일은 | 로 여러 개, 한 열이거나 - 이면 없는 질문") {
        val file = Files.createTempFile("code", ".tsv")
        Files.writeString(file, "# 주석\nSSRF 막는 곳\tUrlGuard.kt|HttpPageFetcher.kt\nJWT 갱신\t-\nGraphQL 스키마\n\n")
        CodeSufficiencyData.rows(file) shouldBe listOf(
            "SSRF 막는 곳" to listOf("UrlGuard.kt", "HttpPageFetcher.kt"),
            "JWT 갱신" to emptyList(),
            "GraphQL 스키마" to emptyList(),
        )
    }

    test("정답 파일이 결정 모델이 보는 글에 들어갔을 때만 1, 못 찾은 질문은 빼고, 없는 질문은 0") {
        val contexts = mapOf(
            "SSRF" to CodeContext("[1] web/UrlGuard.kt\n…", listOf("web/src/main/kotlin/dev/vesta/web/UrlGuard.kt")),
            "리다이렉트" to CodeContext("[1] Router.kt\n…", listOf("router/Router.kt")),
            "JWT" to CodeContext("[1] WebAuth.kt\n…", listOf("app-server/WebAuth.kt")),
        )
        val items = CodeSufficiencyData.build(
            listOf("SSRF" to listOf("UrlGuard.kt"), "리다이렉트" to listOf("HttpPageFetcher.kt"), "JWT" to emptyList(), "검색 결과 없음" to emptyList()),
        ) { contexts[it] }
        items.map { it.question to it.label } shouldBe listOf("SSRF" to true, "JWT" to false)
        val json = Json.parseToJsonElement(items.first().toJson()).jsonObject
        json["q"]!!.jsonPrimitive.content shouldBe "D6"
        json["question"]!!.jsonPrimitive.content shouldBe "SSRF"
        json["label"]!!.jsonPrimitive.int shouldBe 1
    }

    test("재료가 없으면 파일을 만들지 않는다") {
        val target = Files.createTempDirectory("judge").resolve("x.jsonl")
        CodeSufficiencyData.write(emptyList(), target) shouldBe null
        Files.exists(target) shouldBe false
    }
})
