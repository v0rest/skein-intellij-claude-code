package io.github.v0rest.skein.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [DiffTabs]: one Show Changes tab per worktree, remembered while it is open and forgotten when it closes. */
class DiffTabsTest {
    private val open = mutableSetOf<String>()
    private val tabs = DiffTabs<String>()

    private fun current(key: String) = tabs.current(key) { it in open }

    @Test
    fun aWorktreesTabIsFoundAgainWhileItIsOpen() {
        open += "diff-1"
        tabs.opened("/w/acme/.claude/worktrees/task-1", "diff-1")
        assertEquals("diff-1", current("/w/acme/.claude/worktrees/task-1"))
        assertNull("another worktree has its own tab", current("/w/acme/.claude/worktrees/task-2"))
    }

    @Test
    fun aClosedTabIsForgotten() {
        open += "diff-1"
        tabs.opened("task-1", "diff-1")
        tabs.closed("diff-1")
        assertNull(current("task-1"))
    }

    /** A tab closed without a word to Skein is forgotten the next time it is asked for. */
    @Test
    fun aTabNoLongerOpenIsForgottenWhenAskedFor() {
        tabs.opened("task-1", "diff-1")
        assertNull(current("task-1"))
        open += "diff-1"
        assertNull("forgotten for good", current("task-1"))
    }

    /** The refresh opens the new tab first, then closes the old one: that close must not forget the new tab. */
    @Test
    fun closingTheReplacedTabKeepsItsSuccessor() {
        open += listOf("diff-1", "diff-2")
        tabs.opened("task-1", "diff-1")
        tabs.opened("task-1", "diff-2")
        tabs.closed("diff-1")
        assertEquals("diff-2", current("task-1"))
    }

    @Test
    fun theTabShowDiffOpenedIsTheOneNewFile() {
        assertEquals("diff-1", DiffTabs.newTab(setOf("a.kt", "b.kt"), listOf("a.kt", "diff-1", "b.kt")))
        assertNull("no new tab: a diff window, or an external diff tool", DiffTabs.newTab(setOf("a.kt"), listOf("a.kt")))
        assertNull("two new files: no telling which", DiffTabs.newTab(setOf("a.kt"), listOf("a.kt", "x", "y")))
    }
}
