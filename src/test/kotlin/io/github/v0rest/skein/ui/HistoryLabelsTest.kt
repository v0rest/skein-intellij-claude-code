package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryLabelsTest {
    private fun entry(cwd: String, worktree: String? = null) = HistoryEntry("s", cwd, "t", 0L, null, worktree = worktree)

    @Test
    fun aWorktreeEntryShowsTheWorktreeInsteadOfItsFolder() =
        assertEquals("worktree feat-x", HistoryLabels.secondary(entry("/w/acme/.claude/worktrees/feat-x", "feat-x"), "/w/acme") { "folder" })

    @Test
    fun aSubfolderEntryStillShowsItsFolder() =
        assertEquals("docs", HistoryLabels.secondary(entry("/w/acme/docs"), "/w/acme") { it.removePrefix("/w/acme/") })

    @Test
    fun theBaseFolderShowsNothing() = assertNull(HistoryLabels.secondary(entry("/w/acme"), "/w/acme") { "folder" })
}
