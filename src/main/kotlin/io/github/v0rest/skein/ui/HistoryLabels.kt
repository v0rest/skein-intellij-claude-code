package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.HistoryEntry

/** The grey text after a History row's title. */
object HistoryLabels {
    /** `worktree <name>` for a worktree session, else the entry's folder as [label] gives it, or null for the base folder itself. */
    fun secondary(entry: HistoryEntry, base: String?, label: (String) -> String): String? =
        entry.worktree?.let { "worktree $it" } ?: label(entry.cwd).takeIf { entry.cwd != base }
}
