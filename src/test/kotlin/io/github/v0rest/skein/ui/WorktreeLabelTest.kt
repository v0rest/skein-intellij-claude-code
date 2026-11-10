package io.github.v0rest.skein.ui

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
        assertEquals("worktree feat-y", WorktreeLabel.of("feat-x", "feat-x", "/w/acme/.claude/worktrees/feat-y"))
        assertEquals("worktree", WorktreeLabel.of("feat-x", "feat-x", "/w/acme/.claude/worktrees/feat-x/src"))
        // a session in the repository itself, or none yet: the thread's own worktree
        assertEquals("worktree", WorktreeLabel.of("feat-x", "feat-x", "/w/acme"))
        assertEquals("worktree feat-x", WorktreeLabel.of("Fix login", "feat-x", null))
        // a plain thread whose session went into a worktree
        assertEquals("worktree feat-y", WorktreeLabel.of("acme", null, "/w/acme/.claude/worktrees/feat-y"))
        assertNull(WorktreeLabel.of("acme", null, "/w/acme"))
    }
}
