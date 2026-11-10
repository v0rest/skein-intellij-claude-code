package io.github.v0rest.skein.notify

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.v0rest.skein.claude.Json
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.claude.stringOrNull
import io.github.v0rest.skein.threads.NotificationKind

/**
 * The one line a thread row says under its name about what happened, from the tail of the session
 * transcript: the first sentence of the last answer, the tool a permission prompt is about, the question asked, or
 * the tool a working thread runs ([working]). Only the current turn counts, the entries after the user's latest
 * prompt. Pure: takes the transcript lines (oldest first) and never throws on odd input; null when the tail holds
 * nothing usable.
 */
object NotificationContent {
    const val MAX_CHARS = 120
    private const val ELLIPSIS = "…"
    private const val QUESTION_TOOL = "AskUserQuestion"
    private val FILE_TOOLS = setOf("Edit", "MultiEdit", "Write", "Read", "NotebookEdit")
    private val AGENT_TOOLS = setOf("Agent", "Task")

    /** Arguments short and telling enough to show next to a tool's name; a path never is. */
    private val SHORT_ARGUMENTS = listOf("pattern", "url", "query")
    private val AGENT_ARGUMENTS = listOf("description")

    private class ToolUse(val id: String?, val name: String, val input: JsonObject?)

    /** One assistant entry's blocks, in their order. */
    private class Entry(val texts: List<String>, val toolUses: List<ToolUse>)

    /** The assistant entries of the current turn, newest first, and the ids of the tool calls that already have a result. */
    private class Turn(val entries: List<Entry>, val resolved: Set<String>) {
        fun unresolved(tool: ToolUse) = tool.id == null || tool.id !in resolved
    }

    fun fromTail(lines: List<String>, kind: NotificationKind, reason: WaitReason?): String? {
        val turn = currentTurn(lines)
        return when (kind) {
            NotificationKind.TURN_FINISHED -> lastText(turn)
            NotificationKind.NEEDS_YOU -> when (reason) {
                WaitReason.PERMISSION -> pendingTool(turn)?.let(::toolLine)
                WaitReason.QUESTION -> pendingQuestion(turn)?.let(::question) ?: lastText(turn)
                WaitReason.OTHER, null -> lastText(turn)
            }
        }
    }

    /**
     * What a thread at work is doing: the newest tool call of the current turn still without a result, else the
     * first sentence of the turn's last answer so far; null when the turn holds neither.
     */
    fun working(lines: List<String>): String? {
        val turn = currentTurn(lines)
        return runningTool(turn)?.let(::toolLine) ?: lastText(turn)
    }

    /** Walks the tail newest-first up to the user's latest prompt; a line that does not parse (cut at the end of the file) is skipped. */
    private fun currentTurn(lines: List<String>): Turn {
        val entries = ArrayList<Entry>()
        val resolved = HashSet<String>()
        for (obj in lines.asReversed().asSequence().mapNotNull(Json::parseObjectOrNull)) {
            val message = obj.get("message") as? JsonObject ?: continue
            when (obj.stringOrNull("type")) {
                "assistant" -> entries += entryOf(message)
                "user" -> {
                    val content = message.get("content")
                    blocksOf(content).filter { it.stringOrNull("type") == "tool_result" }.mapNotNullTo(resolved) { it.stringOrNull("tool_use_id") }
                    if (isPrompt(obj, content)) break
                }
            }
        }
        return Turn(entries, resolved)
    }

    /** A real prompt the user typed: plain text, or text blocks; a tool result or a meta entry is not one. */
    private fun isPrompt(obj: JsonObject, content: JsonElement?): Boolean {
        val meta = obj.get("isMeta")
        if (meta != null && meta.isJsonPrimitive && meta.asJsonPrimitive.isBoolean && meta.asBoolean) return false
        if (content.isString()) return true
        return blocksOf(content).any { it.stringOrNull("type") == "text" }
    }

    private fun entryOf(message: JsonObject): Entry {
        val content = message.get("content")
        if (content.isString()) return Entry(listOf(content!!.asString), emptyList())
        val blocks = blocksOf(content)
        val texts = blocks.filter { it.stringOrNull("type") == "text" }.mapNotNull { it.stringOrNull("text") }
        val toolUses = blocks.filter { it.stringOrNull("type") == "tool_use" }
            .mapNotNull { block -> block.stringOrNull("name")?.let { ToolUse(block.stringOrNull("id"), it, block.get("input") as? JsonObject) } }
        return Entry(texts, toolUses)
    }

    private fun blocksOf(content: JsonElement?): List<JsonObject> = (content as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonElement?.isString(): Boolean = this != null && isJsonPrimitive && asJsonPrimitive.isString

    /** The first sentence of the newest assistant text of the turn that says anything once the markdown is gone. */
    private fun lastText(turn: Turn): String? =
        turn.entries.asSequence().flatMap { it.texts.asReversed() }.map(::firstSentence).firstOrNull { it.isNotEmpty() }?.let(::cut)

    /**
     * Prompts are asked in order, so the oldest tool call of the turn without a result is the one on screen. Claude
     * Code writes one block per line, so the calls of one message are spread over entries; the turn is the unit.
     */
    private fun pendingTool(turn: Turn): ToolUse? =
        turn.entries.asReversed().asSequence().flatMap { it.toolUses }.firstOrNull(turn::unresolved)

    /** The call started last that has no result yet: what claude is waiting on while it works. */
    private fun runningTool(turn: Turn): ToolUse? =
        turn.entries.asSequence().flatMap { it.toolUses.asReversed() }.firstOrNull(turn::unresolved)

    private fun pendingQuestion(turn: Turn): ToolUse? =
        turn.entries.asSequence().flatMap { it.toolUses.asReversed() }.firstOrNull { it.name == QUESTION_TOOL && turn.unresolved(it) }

    /** `<Tool>: <argument>`, or the tool's name alone when it has no short argument worth showing. */
    private fun toolLine(tool: ToolUse): String? {
        val name = collapse(tool.name)
        if (name.isEmpty()) return null
        val input = tool.input
        val argument = when {
            input == null -> null
            name == "Bash" -> input.stringOrNull("command")?.lineSequence()?.map(::collapse)?.firstOrNull { it.isNotEmpty() }
            name in FILE_TOOLS -> (input.stringOrNull("file_path") ?: input.stringOrNull("notebook_path"))?.let(::fileName)
            else -> (if (name in AGENT_TOOLS) AGENT_ARGUMENTS else SHORT_ARGUMENTS)
                .firstNotNullOfOrNull { input.stringOrNull(it)?.let(::collapse)?.takeIf(String::isNotEmpty) }
        }
        return cut(if (argument.isNullOrEmpty()) name else "$name: $argument")
    }

    private fun question(tool: ToolUse): String? {
        val questions = tool.input?.get("questions") as? JsonArray ?: return null
        val first = questions.firstOrNull() as? JsonObject ?: return null
        return first.stringOrNull("question")?.let(::collapse)?.takeIf(String::isNotEmpty)?.let(::cut)
    }

    /** The last path segment, as text: no Path parsing, so an odd value cannot throw. */
    private fun fileName(path: String): String? = collapse(path.trimEnd('/').substringAfterLast('/')).takeIf(String::isNotEmpty)

    /** A fenced block from its opening line to the matching fence, or to the end when it never closes. */
    private val FENCED_BLOCK = Regex("(?ms)^[ \\t]*(`{3,}|~{3,}).*?(?:^[ \\t]*\\1[ \\t]*$|\\z)")
    private val HEADING = Regex("^[ \\t]*#{1,6}(?:[ \\t]+|$)")
    private val LINE_MARKERS = Regex("^[ \\t]*(?:[-*+][ \\t]+|\\d+[.)][ \\t]+|>[ \\t]?)+")
    private val LINK = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")

    /** Paired emphasis around words only: `__init__.py` and `**kwargs` are names, not marks. */
    private val STRONG = Regex("(?<![A-Za-z0-9_])(\\*\\*|__)([^\\s*_](?:[^\\n]*?[^\\s*_])?)\\1(?![A-Za-z0-9_]|\\.[A-Za-z0-9_])")

    /** A line that is one bold phrase and nothing else: a label like a heading when a blank line follows it. */
    private val BOLD_LINE = Regex("^[ \\t]*(\\*\\*|__)([^\\s*_](?:[^\\n]*?[^\\s*_])?)\\1[ \\t]*$")
    private val LIST_ITEM = Regex("^[ \\t]*(?:[-*+][ \\t]+|\\d+[.)][ \\t]+)")
    private val EMPHASIS_STAR = Regex("(?<![A-Za-z0-9_*])\\*([^\\s*](?:[^*\\n]*?[^\\s*])?)\\*(?![A-Za-z0-9_*])")
    private val EMPHASIS_UNDERSCORE = Regex("(?<![A-Za-z0-9_])_([^\\s_](?:[^_\\n]*?[^\\s_])?)_(?![A-Za-z0-9_])")
    private val STRIKE = Regex("~~([^\\s~](?:[^~\\n]*?[^\\s~])?)~~")

    /** `.`, `!`, `?` close a sentence before whitespace or the end; the CJK stops close one anywhere. */
    private val SENTENCE_END = Regex("[.!?]+[\"')\\]]*(?=\\s|$)|[。！？]+")
    private val WHITESPACE = Regex("(?U)\\s+")

    /**
     * The first sentence of a markdown text: fenced blocks dropped, then line by line (markers and inline marks
     * stripped) until a sentence end, a heading line, a list item or a blank line. Headings, and a bold-only line
     * before a blank one, are labels, not what happened, so they are skipped while ordinary text follows; only an
     * answer made of nothing but labels gives its first one.
     */
    private fun firstSentence(text: String): String {
        val sentence = StringBuilder()
        var firstHeading: String? = null
        val lines = text.replace(FENCED_BLOCK, "\n").split('\n')
        for ((i, raw) in lines.withIndex()) {
            val nextIsBlank = i + 1 >= lines.size || lines[i + 1].isBlank()
            val heading = HEADING.containsMatchIn(raw) || (BOLD_LINE.matches(raw) && nextIsBlank)
            val line = inline(raw.replace(HEADING, "").replace(LINE_MARKERS, ""))
            if (line.isEmpty()) {
                if (sentence.isNotEmpty()) break else continue
            }
            if (heading) {
                if (sentence.isNotEmpty()) break
                if (firstHeading == null) firstHeading = line
                continue
            }
            if (sentence.isNotEmpty() && LIST_ITEM.containsMatchIn(raw)) break
            if (sentence.isNotEmpty()) sentence.append(' ')
            sentence.append(line)
            val end = SENTENCE_END.find(sentence)
            if (end != null) return sentence.substring(0, end.range.last + 1).trim()
        }
        return sentence.toString().trim().ifEmpty { firstHeading?.let(::upToSentenceEnd).orEmpty() }
    }

    private fun upToSentenceEnd(text: String): String = SENTENCE_END.find(text)?.let { text.substring(0, it.range.last + 1) }?.trim() ?: text

    private fun inline(line: String): String =
        collapse(
            line.replace("`", "").replace(LINK, "$1").replace(STRONG, "$2").replace(EMPHASIS_STAR, "$1").replace(EMPHASIS_UNDERSCORE, "$1").replace(STRIKE, "$1"),
        )

    internal fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

    /** At most [MAX_CHARS] code points, the last one an ellipsis when something was left out; never splits a character. */
    internal fun cut(text: String): String {
        if (text.codePointCount(0, text.length) <= MAX_CHARS) return text
        return text.substring(0, text.offsetByCodePoints(0, MAX_CHARS - 1)).trimEnd() + ELLIPSIS
    }
}
