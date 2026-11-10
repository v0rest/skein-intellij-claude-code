package io.github.v0rest.skein.claude

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** Lenient helpers over Gson for the JSON Claude Code writes: nothing here throws on odd input. */
internal object Json {
    /** One JSON value, or null when the text is blank or malformed. */
    fun parseOrNull(text: String): JsonElement? {
        if (text.isBlank()) return null
        return try {
            JsonParser.parseString(text)
        } catch (e: JsonParseException) {
            null
        }
    }

    /** One JSON object, or null when the text is blank, malformed or not an object. */
    fun parseObjectOrNull(text: String): JsonObject? = parseOrNull(text) as? JsonObject
}

internal fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) ?: return null
    return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
}

/** A number as a long, or null when absent, not a number, or a value Gson cannot convert (an absurd exponent). */
internal fun JsonObject.longOrNull(key: String): Long? {
    val value = get(key) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    return try {
        value.asLong
    } catch (e: NumberFormatException) {
        null
    }
}
