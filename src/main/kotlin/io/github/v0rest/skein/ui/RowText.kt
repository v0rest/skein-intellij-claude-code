package io.github.v0rest.skein.ui

import com.intellij.openapi.util.text.StringUtil
import io.github.v0rest.skein.threads.ContextUse
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState

/** Text decisions of thread rows and History rows that need no Swing. */
object RowText {
    /** A thread that waits for the user, or finished a turn the user has not looked at, is shown in bold. */
    fun bold(state: ThreadState): Boolean = state is ThreadState.NeedsYou || state is ThreadState.YourTurn

    /**
     * One thing on each line: the name when the row cuts it ([cutName]), the state words with when claude last answered,
     * the model and the context in use ([context]), then a worktree's [lines] ([WorktreeBadge.tooltip]). No preview and
     * no path: the row shows what fits of the one and names the folder or the worktree of the other. Always HTML with
     * every line escaped: names come from Claude Code, git and the user, and a tooltip that starts with `<html>` is read
     * as HTML, remote images included, so none of them can ever be markup.
     */
    fun tooltip(thread: SkeinThread, cutName: String? = null, context: ContextUse? = null, now: Long = 0, lines: List<String> = emptyList()): String {
        val state = listOfNotNull(StatusText.tooltipLine(thread.state), context?.let { answered(it, now) }).joinToString(" · ")
        return "<html>" + (listOfNotNull(cutName, state, context?.tooltipBase) + lines).joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
    }

    /**
     * A row tooltip: [parts] joined by " - ", then each of [lines] on a line of its own, as HTML with every part and
     * line escaped, so none of them can ever be markup.
     */
    fun escapedTooltip(parts: List<String>, lines: List<String> = emptyList()): String =
        "<html>" + StringUtil.escapeXmlEntities(parts.joinToString(" - ")) + lines.joinToString("") { "<br>" + StringUtil.escapeXmlEntities(it) }

    /** The row's second line: the [worktree] mark when there is one, then the [preview], or the state words without one. */
    fun secondLine(state: ThreadState, worktree: String?, preview: String?): String =
        listOfNotNull(worktree, preview ?: StatusText.line(state)).joinToString(" · ")

    /** When claude last answered, as "2 min ago" or "just now"; null when the transcript did not say. */
    fun answered(use: ContextUse, now: Long): String? =
        use.at?.let { StatusText.age(it, now).let { age -> if (age == "now") "just now" else "$age ago" } }
}
