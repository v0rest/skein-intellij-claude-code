package io.github.v0rest.skein.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.JBUI
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.git.Changes
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.threads.KeptKind

/**
 * What a worktree row shows on line 1 before the age: [changes] (`✎3`, the word of an operation in progress, or `?`)
 * in the [look] it asks for, then [ahead] (`↑2`) in grey. Null parts are not shown.
 */
data class Badge(val changes: String?, val ahead: String?, val look: Badge.Look) {
    enum class Look {
        /** Uncommitted work, or an operation stopped half way. */
        AMBER,

        /** Nothing that needs the owner: commits ahead alone, or git could not tell. */
        GREY,
    }
}

/** The pure part of the worktree counts on a row: the badge, the tooltip lines and what a screen reader says. */
object WorktreeBadge {
    const val CHANGES = "✎"
    const val AHEAD = "↑"
    const val UNKNOWN = "?"

    /**
     * The badge for [counts]; null when there is nothing to show (clean and level, no counts, or none read yet). A
     * detached HEAD or a missing base shows the changes only, an operation in progress its word alone.
     */
    fun of(counts: WorktreeCounts?): Badge? {
        return when (counts) {
            null, WorktreeCounts.NotShown -> null
            is WorktreeCounts.Failed -> Badge(UNKNOWN, null, Badge.Look.GREY)
            is WorktreeCounts.InProgress -> Badge(counts.operation.word, null, Badge.Look.AMBER)
            is WorktreeCounts.Counted -> {
                val changes = counts.changes.count.takeIf { it > 0 }?.let { "$CHANGES$it" }
                val ahead = counts.ahead?.takeIf { it > 0 }?.let { "$AHEAD$it" }
                when {
                    changes == null && ahead == null -> null
                    else -> Badge(changes, ahead, if (changes != null) Badge.Look.AMBER else Badge.Look.GREY)
                }
            }
        }
    }

    /**
     * The tooltip lines for [counts], plain text: up to [Changes.SHOWN] changed paths, numbered, and how many more, then
     * how the branch stands against its base, or "Clean, level with <base>"; git's reason when it failed. A long line is
     * wrapped ([wrap]).
     */
    fun tooltip(counts: WorktreeCounts?): List<String> {
        return when (counts) {
            null, WorktreeCounts.NotShown -> emptyList()
            is WorktreeCounts.Failed -> wrap("git could not read the worktree: ${counts.reason}")
            is WorktreeCounts.InProgress -> listOf(counts.operation.described) + paths(counts.changes)
            is WorktreeCounts.Counted -> paths(counts.changes) + standing(counts)
        }
    }

    /** The longest line a worktree tooltip has, in characters; a longer one wraps ([wrap]). */
    const val MAX_CHARS = 60

    /** The tooltip's width once a line had to wrap, before the IDE's scaling ([defaultWidthPx]). */
    private const val WIDTH = 420

    private const val NBSP = '\u00A0'

    /** [WIDTH] as the IDE scales it. */
    fun defaultWidthPx(): Int = JBUI.scale(WIDTH)

    /**
     * [line] cut into lines of at most [max] characters, after a `/` or at a space, a part with neither cut where it
     * must be; every line after the first starts with [indent] no-break spaces ([isContinuation]). Swing's HTML breaks
     * a line only where Java's line breaker allows it, never after a `/`, so a long path would make the tooltip as wide
     * as itself.
     */
    fun wrap(line: String, max: Int = MAX_CHARS, indent: Int = 2): List<String> {
        if (line.length <= max) return listOf(line)
        val pad = NBSP.toString().repeat(indent.coerceIn(0, max - 1))
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var start = 0
        for (piece in Regex("[^/ ]*[/ ]|[^/ ]+").findAll(line).map { it.value }) {
            var rest = piece
            while (rest.isNotEmpty()) {
                if ((current.toString() + rest).trimEnd(' ').length <= max) {
                    current.append(rest)
                    break
                }
                if (current.length > start) {
                    // the line so far is full: the piece starts the next one
                    lines += current.toString().trimEnd(' ')
                } else {
                    // the piece alone is longer than a line: cut it
                    val room = max - current.length
                    lines += current.append(rest.take(room)).toString()
                    rest = rest.drop(room)
                }
                current.setLength(0)
                current.append(pad)
                start = pad.length
                rest = rest.trimStart(' ')
            }
        }
        if (current.length > start) lines += current.toString().trimEnd(' ')
        return lines
    }

    /** Whether [line] goes on from the one before it ([wrap]). */
    fun isContinuation(line: String): Boolean = line.startsWith(NBSP)

    /**
     * The folder a Worktrees section row names in its tooltip: the worktree [name] of [repository] (as the project names
     * it) from the project's [projectBase], `.claude/worktrees/task-1` say, or the whole resolved [path] for a
     * repository outside the project.
     */
    fun folder(repository: String, name: String, path: String, projectBase: String?): String {
        val base = projectBase?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return path
        val named = Worktrees.dirOf(repository, name)
        return if (named.startsWith("$base/")) named.removePrefix("$base/") else path
    }

    /**
     * A Worktrees section row's tooltip: its [folder], the [hint] about it and the counts' [lines] ([tooltip]), each on
     * its own line and wrapped, as HTML with every line escaped. [widthPx] (or [defaultWidthPx]) is its width once a line
     * had to wrap; without one it is as wide as its longest line.
     */
    fun sectionTooltip(folder: String, hint: List<String>, lines: List<String>, widthPx: Int? = null): String =
        html((wrap(folder) + hint.flatMap { wrap(it) } + lines), widthPx)

    /** [lines] as tooltip HTML, every one escaped, with a body width only when one of them wraps ([isContinuation]). */
    fun html(lines: List<String>, widthPx: Int? = null): String {
        val width = if (lines.any(::isContinuation)) "<body style=\"width: ${widthPx ?: defaultWidthPx()}px\">" else ""
        return "<html>" + width + lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
    }

    /** What a screen reader hears of [counts]; null when the row shows nothing. */
    fun spoken(counts: WorktreeCounts?): String? = when (counts) {
        null, WorktreeCounts.NotShown -> null
        is WorktreeCounts.Failed -> "git could not read the worktree"
        is WorktreeCounts.InProgress -> counts.operation.described
        is WorktreeCounts.Counted -> listOfNotNull(
            counts.changes.count.takeIf { it > 0 }?.let { count(it, "changed file", "changed files") },
            counts.ahead?.takeIf { it > 0 }?.let { "${count(it, "commit", "commits")} not in ${counts.base}" },
        ).joinToString(", ").ifEmpty { null }
    }

    /** The second line of a Worktrees section row: the branch, or what git says the folder is. */
    fun kind(kind: KeptKind): String = when (kind) {
        is KeptKind.Registered -> (kind.branch ?: "detached HEAD") + if (kind.locked) " · locked" else ""
        KeptKind.Unregistered -> "not a git worktree"
        is KeptKind.Unlinked -> if (kind.ownRepository) "a repository of its own" else "worktree git links elsewhere"
        is KeptKind.Unknown -> "git could not list the worktrees"
    }

    /**
     * What a section row says before its counts in the tooltip: for a folder with a `.git` that git does not list here,
     * what it is and, for a worktree linked to another path, how to link it here again; nothing for the others.
     */
    fun hint(kind: KeptKind, path: String): List<String> = when {
        kind !is KeptKind.Unlinked -> emptyList()
        kind.ownRepository -> listOf("A repository of its own, so Skein never deletes it.")
        else -> listOf(
            "A worktree git links to another path, as after the repository moved or was copied, so Skein never deletes it.",
            "To link it here again, run git worktree repair $path in the repository.",
        )
    }

    /** The paths numbered, a long one wrapped under its number, then how many more there are. */
    private fun paths(changes: Changes): List<String> {
        val more = changes.count - changes.first.size
        val numbered = changes.first.withIndex().flatMap { (i, path) -> "${i + 1}. ".let { number -> wrap(number + path, indent = number.length) } }
        return numbered + listOfNotNull("… and $more more".takeIf { more > 0 })
    }

    private fun standing(counts: WorktreeCounts.Counted): String {
        val clean = counts.changes.count == 0
        val base = counts.base
        val ahead = counts.ahead
        return when {
            counts.detached -> if (clean) "Clean, detached HEAD" else "Detached HEAD, on no branch"
            base == null || ahead == null -> if (clean) "Clean, no base branch to compare with" else "No base branch to compare with"
            ahead > 0 -> "${count(ahead, "commit", "commits")} not in $base"
            clean -> "Clean, level with $base"
            else -> "Level with $base"
        }
    }

    private fun count(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"
}
