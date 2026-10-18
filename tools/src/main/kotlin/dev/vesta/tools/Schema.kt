package dev.vesta.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** JSON Schema 속성 하나 */
internal fun prop(type: String, description: String): Pair<String, String> = type to description

/** 도구 인자 JSON Schema (object). */
internal fun schema(vararg properties: Pair<String, Pair<String, String>>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (name, typeAndDescription) ->
                putJsonObject(name) {
                    put("type", typeAndDescription.first)
                    put("description", typeAndDescription.second)
                }
            }
        }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
