package io.github.v0rest.skein.threads

import com.google.gson.JsonObject
import io.github.v0rest.skein.claude.Json
import io.github.v0rest.skein.claude.longOrNull
import io.github.v0rest.skein.claude.stringOrNull
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The model a thread's claude last answered with and the context it had in use then, from the transcript tail: the
 * last main-thread assistant entry that carries `message.usage`. [tokens] is what the answer read in (fresh input
 * plus cache writes and reads, never the output); [at] is the entry's time in epoch millis, null when it has none or
 * none that parses. A data class, so an unchanged re-read compares equal and repaints nothing. The texts a row needs
 * ([family], [label], [tooltipBase], [spoken]) are made once here, so a renderer only copies them.
 */
data class ContextUse(val model: String, val tokens: Long, val at: Long?) {
    /**
     * `Opus`, `Sonnet`, `Haiku` or `Fable`; any other id without its `claude-` prefix and `[1m]`-style suffix, cut to
     * 12 characters plus an ellipsis.
     */
    val family: String = familyOf(model)

    /** What the row shows, for example `Opus · 385k`. */
    val label: String = "$family · ${tokenLabel(tokens)}"

    /**
     * The tooltip without the time: the full model id and the exact count, for example `claude-opus-5-5 · 385,279
     * tokens in context`.
     */
    val tooltipBase: String = "$model · ${if (tokens == 1L) "1 token" else "${exact(tokens)} tokens"} in context"

    /** What a screen reader hears instead of [label]. */
    val spoken: String = "$family, ${tokenLabel(tokens)} tokens of context"

    /** What a look at a transcript tail found: an answer, a compaction after the last answer, or neither. */
    sealed interface Scan {
        data class Found(val use: ContextUse) : Scan

        /** The context was compacted after the last answer: nothing is known until the next answer. */
        data object Reset : Scan

        /**
         * The tail holds no answer and no compaction (the entry dropped out of the 64 KB window): keep what was
         * known.
         */
        data object NothingInTail : Scan
    }

    companion object {
        private val FAMILIES = mapOf("opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku", "fable" to "Fable")
        private val SEPARATORS = Regex("[-./:_]")
        private const val MAX_ID_CHARS = 12
        private const val ELLIPSIS = "…"

        /**
         * The newest usable entry of [lines] (oldest first); null when there is none or a compaction came after it.
         */
        fun fromTail(lines: List<String>): ContextUse? = (scan(lines) as? Scan.Found)?.use

        /**
         * Walks [lines] newest first: the first answer or compaction marker decides. Pure, never throws on odd input.
         */
        fun scan(lines: List<String>): Scan {
            for (obj in lines.asReversed().asSequence().mapNotNull(Json::parseObjectOrNull)) {
                if (isCompaction(obj)) return Scan.Reset
                parse(obj)?.let { return Scan.Found(it) }
            }
            return Scan.NothingInTail
        }

        private fun isCompaction(obj: JsonObject): Boolean {
            if (obj.stringOrNull("type") == "system" && obj.stringOrNull("subtype") == "compact_boundary") return true
            val flag = obj.get("isCompactSummary")
            return flag != null && flag.isJsonPrimitive && flag.asJsonPrimitive.isBoolean && flag.asBoolean
        }

        private fun parse(obj: JsonObject): ContextUse? {
            if (obj.stringOrNull("type") != "assistant") return null
            val sidechain = obj.get("isSidechain")
            if (sidechain != null && sidechain.isJsonPrimitive && sidechain.asJsonPrimitive.isBoolean && sidechain.asBoolean) return null
            val message = obj.get("message") as? JsonObject ?: return null
            val usage = message.get("usage") as? JsonObject ?: return null
            val model = message.stringOrNull("model")?.takeIf { it.isNotBlank() && !it.startsWith("<") } ?: return null
            val tokens = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
                .sumOf { (usage.longOrNull(it) ?: 0L).coerceAtLeast(0L) }
            if (tokens <= 0L) return null
            val at = obj.stringOrNull("timestamp")?.let {
                try {
                    Instant.parse(it).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                } catch (_: ArithmeticException) {
                    null
                }
            }
            return ContextUse(model, tokens, at)
        }

        private fun familyOf(model: String): String {
            val id = model.substringBefore('[').trim()
            id.split(SEPARATORS).firstNotNullOfOrNull { FAMILIES[it] }?.let { return it }
            val short = id.removePrefix("claude-")
            return if (short.length > MAX_ID_CHARS) short.take(MAX_ID_CHARS) + ELLIPSIS else short
        }

        /** 999, 8.4k, 385k, 1.2M: rounded down, so a label never claims more than is there. */
        fun tokenLabel(tokens: Long): String = when {
            tokens < 1_000 -> tokens.toString()
            tokens < 10_000 -> "${tokens / 1_000}.${tokens % 1_000 / 100}k"
            tokens < 1_000_000 -> "${tokens / 1_000}k"
            else -> "${tokens / 1_000_000}.${tokens % 1_000_000 / 100_000}M"
        }

        /** The exact count with thousands separators (always commas, whatever the IDE's locale). */
        fun exact(tokens: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(tokens)
    }
}
