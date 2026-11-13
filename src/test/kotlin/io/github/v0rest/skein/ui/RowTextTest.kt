package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.ContextUse
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowTextTest {
    private fun thread(state: ThreadState, sessionCwd: String? = null) =
        SkeinThread("t1", "/w/acme", null, "acme", state, 0, 0, null, sessionCwd = sessionCwd)

    @Test
    fun needsYouAndYourTurnAreBold() {
        assertTrue(RowText.bold(ThreadState.NeedsYou(WaitReason.PERMISSION, 0)))
        assertTrue(RowText.bold(ThreadState.YourTurn(0)))
        listOf(
            ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Idle, ThreadState.Starting,
            ThreadState.NotStarted, ThreadState.Exited, ThreadState.Unknown, ThreadState.RunningElsewhere(1),
        ).forEach { assertFalse("$it", RowText.bold(it)) }
    }

    @Test
    fun theTooltipSaysTheStateAndNoPath() {
        assertEquals("<html>waiting for your permission", RowText.tooltip(thread(ThreadState.NeedsYou(WaitReason.PERMISSION, 0))))
        assertEquals("<html>idle", RowText.tooltip(thread(ThreadState.Idle)))
        // the folder is on the row already (its name or the worktree mark): no long path in the tooltip
        assertEquals("<html>idle", RowText.tooltip(thread(ThreadState.Idle, sessionCwd = "/w/acme/.claude/worktrees/feat-x")))
    }

    @Test
    fun theTooltipPutsOneThingOnEachLine() {
        val now = 1_000_000_000_000L
        val use = ContextUse("claude-opus-5-5", 385_279, now - 2 * 60_000)
        assertEquals(
            "<html>idle · 2 min ago<br>claude-opus-5-5 · 385,279 tokens in context<br>draft.txt<br>1 commit not in main",
            RowText.tooltip(thread(ThreadState.Idle), context = use, now = now, lines = listOf("draft.txt", "1 commit not in main")),
        )
        assertEquals("no time known, none shown", "<html>idle<br>claude-opus-5-5 · 12 tokens in context", RowText.tooltip(thread(ThreadState.Idle), context = ContextUse("claude-opus-5-5", 12, null), now = now))
    }

    @Test
    fun aNameTheRowCutsLeadsTheTooltip() {
        assertEquals("<html>Fix the login redirect<br>idle", RowText.tooltip(thread(ThreadState.Idle), cutName = "Fix the login redirect"))
    }

    @Test
    fun theTooltipIsNeverReadAsHtml() {
        // names come from Claude Code and the user, and a tooltip starting with <html> is rendered as HTML
        val name = """<html><img src="https://example.test/x.png">acme & co"""
        val tooltip = RowText.tooltip(thread(ThreadState.Idle, sessionCwd = "/w/<b>acme</b>"), cutName = name)
        assertEquals("<html>&lt;html&gt;&lt;img src=&quot;https://example.test/x.png&quot;&gt;acme &amp; co<br>idle", tooltip)
        assertFalse("no markup but the line breaks", tooltip.removePrefix("<html>").replace("<br>", "").contains('<'))
    }

    @Test
    fun theSecondLineIsTheWorktreeMarkThenThePreviewOrTheState() {
        assertEquals("Tests are green.", RowText.secondLine(ThreadState.Idle, worktree = null, preview = "Tests are green."))
        assertEquals("idle", RowText.secondLine(ThreadState.Idle, worktree = null, preview = null))
        assertEquals("worktree feat-x · Tests are green.", RowText.secondLine(ThreadState.Idle, worktree = "worktree feat-x", preview = "Tests are green."))
        assertEquals("worktree feat-x · working", RowText.secondLine(ThreadState.Working, worktree = "worktree feat-x", preview = null))
    }
}
