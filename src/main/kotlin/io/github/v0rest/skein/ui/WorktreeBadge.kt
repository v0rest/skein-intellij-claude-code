package io.github.v0rest.skein.ui

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
     * The tooltip lines for [counts], plain text: up to [Changes.SHOWN] changed paths and how many more, then how the
     * branch stands against its base, or "Clean, level with <base>"; git's reason when it failed.
     */
    fun tooltip(counts: WorktreeCounts?): List<String> {
        return when (counts) {
            null, WorktreeCounts.NotShown -> emptyList()
            is WorktreeCounts.Failed -> listOf("git could not read the worktree: ${counts.reason}")
            is WorktreeCounts.InProgress -> listOf(counts.operation.described) + paths(counts.changes)
            is WorktreeCounts.Counted -> paths(counts.changes) + standing(counts)
        }
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

    private fun paths(changes: Changes): List<String> {
        val more = changes.count - changes.first.size
        return changes.first + listOfNotNull("… and $more more".takeIf { more > 0 })
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
