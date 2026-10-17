package dev.vesta.decision

import dev.vesta.core.decision.DecisionAnswer
import dev.vesta.core.decision.DecisionClient
import dev.vesta.core.decision.DecisionQuestion
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Ollaya `/api/decide` 클라이언트 (PROJECT.md 4.2). 기본 주소 `http://127.0.0.1:11435`. */
class OllayaClient(
    private val baseUrl: String,
    private val model: String,
    private val http: HttpClient,
) : DecisionClient {
    override suspend fun decide(state: String, questions: Map<String, DecisionQuestion>): Map<String, DecisionAnswer> {
        val response = http.post("$baseUrl/api/decide") {
            contentType(ContentType.Application.Json)
            setBody(OllayaWire.request(model, state, questions).toString())
        }
        val text = response.bodyAsText()
        check(response.status.isSuccess()) { "Ollaya HTTP ${response.status.value}: ${text.take(300)}" }
        return OllayaWire.answers(text)
    }
}

internal object OllayaWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(model: String, state: String, questions: Map<String, DecisionQuestion>): JsonObject = buildJsonObject {
        put("model", model)
        put("state", state)
        putJsonObject("questions") {
            questions.forEach { (key, question) ->
                putJsonObject(key) {
                    put("instructions", question.instructions)
                    when (question) {
                        is DecisionQuestion.Choice -> {
                            put("type", "choice")
                            putJsonObject("criteria") { question.criteria.forEach { (k, v) -> put(k, v) } }
                        }
                        is DecisionQuestion.Score -> {
                            put("type", "score")
                            putJsonArray("levels") { question.levels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        }
                        is DecisionQuestion.Noul -> put("type", "noul")
                    }
                }
            }
        }
    }

    fun answers(text: String): Map<String, DecisionAnswer> {
        val answers = json.parseToJsonElement(text).jsonObject["answers"]?.jsonObject ?: return emptyMap()
        return answers.mapValues { (_, value) ->
            val obj = value.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "choice" -> DecisionAnswer.Choice(
                    value = obj.getValue("choice").jsonPrimitive.content,
                    confidence = obj["confidence"]?.jsonPrimitive?.double ?: 0.0,
                    probabilities = obj["probabilities"]?.jsonObject?.mapValues { it.value.jsonPrimitive.double }.orEmpty(),
                )
                "score" -> DecisionAnswer.Score(obj.getValue("score").jsonPrimitive.double, obj["confidence"]?.jsonPrimitive?.double ?: 0.0)
                else -> DecisionAnswer.Noul(obj.getValue("noul").jsonPrimitive.double)
            }
        }
    }
}
