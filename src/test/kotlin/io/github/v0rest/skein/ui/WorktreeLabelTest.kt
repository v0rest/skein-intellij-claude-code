package io.github.v0rest.skein.ui

import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorktreeLabelTest {
    @Test
    fun theNameIsLeftOutWhenTheRowAlreadyShowsIt() {
        assertEquals("worktree", WorktreeLabel.of("feat-x", "feat-x"))
    }

    @Test
    fun theNameFollowsWhenTheRowShowsSomethingElse() {
        assertEquals("worktree feat-x", WorktreeLabel.of("Fix login", "feat-x"))
    }

    @Test
    fun noLabelOutsideAWorktree() {
        assertNull(WorktreeLabel.of("acme", null))
    }

    /** The mark follows the worktree the session runs in, which need not be the one the thread started in. */
    @Test
    fun theMarkFollowsTheSessionsWorktree() {
        assertEquals("worktree feat-y", WorktreeLabel.of("feat-x", "feat-x", "/w/acme/.claude/worktrees/feat-y", UNIX))
        assertEquals("worktree", WorktreeLabel.of("feat-x", "feat-x", "/w/acme/.claude/worktrees/feat-x/src", UNIX))
        // a session in the repository itself, or none yet: the thread's own worktree
        assertEquals("worktree", WorktreeLabel.of("feat-x", "feat-x", "/w/acme", UNIX))
        assertEquals("worktree feat-x", WorktreeLabel.of("Fix login", "feat-x", null, UNIX))
        // a plain thread whose session went into a worktree
        assertEquals("worktree feat-y", WorktreeLabel.of("acme", null, "/w/acme/.claude/worktrees/feat-y", UNIX))
        assertNull(WorktreeLabel.of("acme", null, "/w/acme", UNIX))
    }

    /** A Windows status file names the cwd with backslashes. */
    @Test
    fun theMarkFollowsTheSessionsWorktreeOnWindows() {
        assertEquals("worktree feat-y", WorktreeLabel.of("feat-x", "feat-x", "C:\\w\\acme\\.claude\\worktrees\\feat-y", WINDOWS))
        assertEquals("worktree", WorktreeLabel.of("feat-x", "feat-x", "C:\\w\\acme\\.claude\\worktrees\\feat-x\\src", WINDOWS))
        assertEquals("worktree feat-x", WorktreeLabel.of("Fix login", "feat-x", "C:\\w\\acme", WINDOWS))
        // the same text on Unix is one name with backslashes in it, no worktree
        assertNull(WorktreeLabel.of("acme", null, "C:\\w\\acme\\.claude\\worktrees\\feat-y", UNIX))
    }
}
