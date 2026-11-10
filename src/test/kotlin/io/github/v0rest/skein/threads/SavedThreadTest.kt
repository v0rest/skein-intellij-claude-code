package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the workspace file keeps of a thread, and the thread it gives back after a restart. */
class SavedThreadTest {
    @Test
    fun aWorktreeThreadComesBackWithItsBase() {
        val thread = SkeinThread(
            "55555555-5555-4555-8555-555555555555", "/w/acme", "11111111-1111-4111-8111-111111111111", "Fix login", ThreadState.Idle, 9, 5, 42,
            pinned = true, color = ThreadColor.BLUE, sessionCwd = "/w/acme/.claude/worktrees/acme", worktree = "acme", base = "main",
        )
        val back = ThreadService.restored(ThreadService.saved(thread))!!
        assertEquals(
            thread.copy(state = ThreadState.NotStarted, stateSince = 0, anchor = null, sessionCwd = null),
            back,
        )
        assertEquals("main", back.base)
    }

    @Test
    fun aThreadSavedBeforeBasesExistedHasNone() {
        val saved = ThreadService.SavedThread().apply {
            id = "t1"
            folder = "/w/acme"
            worktree = "acme"
        }
        assertNull(ThreadService.restored(saved)!!.base)
        assertNull(ThreadService.restored(ThreadService.SavedThread().apply { folder = "/w/acme" }))
    }
}
