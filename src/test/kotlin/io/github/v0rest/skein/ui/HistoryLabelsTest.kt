package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryLabelsTest {
    /** A transcript's cwd as the OS writes it, and the project's base as the IDE does. */
    private fun r(relative: String) = Fixture.root(relative)

    private fun f(relative: String) = Fixture.forwardRoot(relative)

    private fun entry(cwd: String, worktree: String? = null) = HistoryEntry("s", cwd, "t", 0L, null, worktree = worktree)

    @Test
    fun aWorktreeEntryShowsTheWorktreeInsteadOfItsFolder() =
        assertEquals("worktree feat-x", HistoryLabels.secondary(entry(r("w/acme/.claude/worktrees/feat-x"), "feat-x"), f("w/acme")) { "folder" })

    @Test
    fun aSubfolderEntryStillShowsItsFolder() =
        assertEquals("docs", HistoryLabels.secondary(entry(r("w/acme/docs")), f("w/acme")) { it.removePrefix(r("w/acme") + PathStyle.current.separator) })

    @Test
    fun theBaseFolderShowsNothing() = assertNull(HistoryLabels.secondary(entry(r("w/acme")), f("w/acme")) { "folder" })
}
