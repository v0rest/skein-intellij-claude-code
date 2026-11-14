package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/** The grey text after a History row's title. */
object HistoryLabels {
    /**
     * `worktree <name>` for a worktree session, else the entry's folder as [label] gives it, or null for the base folder
     * itself. A transcript writes its cwd with the OS's separators and the IDE its base with `/`, hence [PathRules.same].
     */
    fun secondary(entry: HistoryEntry, base: String?, label: (String) -> String): String? = secondary(entry, base, label, PathStyle.current)

    /** As above with the path rules of [style], for a test that runs Windows paths on another OS. */
    fun secondary(entry: HistoryEntry, base: String?, label: (String) -> String, style: PathStyle): String? =
        entry.worktree?.let { "worktree $it" } ?: label(entry.cwd).takeIf { base == null || !PathRules.same(entry.cwd, base, style) }
}
