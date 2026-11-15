package io.github.v0rest.skein.terminal

import com.intellij.terminal.frontend.view.TerminalView
import io.github.v0rest.skein.claude.TranscriptTitle

/**
 * The one thing Skein ever types into a running claude, rather than into a shell ([ClaudeCommand]): `/rename <name>`,
 * Claude Code's own way to name a session, which writes a custom-title record into the transcript, so `claude -r` shows
 * the name. The tab is a shell underneath claude, and if claude has gone by the time the line arrives, zsh, bash, fish,
 * PowerShell or cmd runs it instead; so the name is held to what none of them reads as anything but plain words -
 * letters and digits of any script, spaces and `- _ . , : + # /`, trimmed, at most [MAX_LENGTH] characters. Anything
 * else is refused rather than quoted, as ClaudeCommand does.
 */
object ClaudeRename {
    const val MAX_LENGTH = 100

    /** The marks a name may hold besides letters and digits; none of them means anything to a shell inside a word. */
    private const val MARKS = " -_.,:+#/"

    /** Why [name] cannot go to Claude Code, in words for the Rename dialog; null when it can. */
    fun refusal(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "Claude Code gets no empty name from Skein."
        val length = clean.codePointCount(0, clean.length)
        if (length > MAX_LENGTH) return "Skein types at most $MAX_LENGTH characters into claude, this name has $length."
        val bad = clean.codePoints().filter { !allowed(it) }.findFirst()
        if (bad.isPresent) return "Skein types only letters, digits, spaces and - _ . , : + # / into claude, not ${shown(bad.asInt)}."
        return null
    }

    fun isAllowed(name: String): Boolean = refusal(name) == null

    /** `/rename <name>` for a name [isAllowed] lets through, trimmed; throws for any other. */
    fun line(name: String): String {
        require(isAllowed(name)) { "not a name Skein types into claude" }
        return "/rename " + name.trim()
    }

    /**
     * Whether the transcript tail [lines] shows [name] as Claude Code's own name of the session, its latest custom-title
     * record ([TranscriptTitle]); an AI title or a prompt that reads the same is not that.
     */
    fun confirmedBy(lines: List<String>, name: String): Boolean =
        TranscriptTitle.ranked(lines)?.let { it.rank == 0 && it.title == name.trim() } == true

    /**
     * Whether the tab takes the line right now, on the EDT right before it goes: it is not closed ([disposed]), Skein is
     * not ending or closing it ([ending], [released]), its terminal session runs ([running]) and claude's input there is
     * clean ([inputClean], [DraftGuard.allowsSend]).
     */
    fun tabReady(disposed: Boolean, ending: Boolean, released: Boolean, running: Boolean, inputClean: Boolean): Boolean =
        !disposed && !ending && !released && running && inputClean

    /**
     * Types `/rename <name>` and Enter into [view], the tab's claude, and nothing else: the only place in Skein that types
     * into a running claude. The caller has made sure right before, in the same EDT turn, that claude runs there, idle,
     * with nothing typed since the last Enter; [line] checks the name once more. The builder's bracketed paste is opt-in and
     * left off, as on the Windows VM where claude took this very line as its command (2026-11-15). EDT only.
     */
    internal fun send(view: TerminalView, name: String) {
        val line = line(name)
        view.createSendTextBuilder().shouldExecute().send(line)
    }

    // the marks are all ASCII, and a code point past it must not pass as one of them once cut to a Char
    private fun allowed(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) || (codePoint < 0x80 && MARKS.indexOf(codePoint.toChar()) >= 0)

    /**
     * A character as the dialog names it: itself in quotes when it can be seen, else its code point, and so are `<`, `>`
     * and `&`, which the dialog might take for markup.
     */
    private fun shown(codePoint: Int): String =
        if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || !Character.isDefined(codePoint) ||
            Character.getType(codePoint) == Character.FORMAT.toInt() || codePoint == '<'.code || codePoint == '>'.code || codePoint == '&'.code
        ) {
            "U+%04X".format(codePoint)
        } else {
            "\"" + String(Character.toChars(codePoint)) + "\""
        }
}
