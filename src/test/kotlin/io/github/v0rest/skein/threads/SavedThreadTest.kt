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

    /** A thread New Thread Here started in a subfolder comes back there, to resume where it ran. */
    @Test
    fun aThreadInASubfolderComesBackThere() {
        val thread = SkeinThread("t2", "/w/acme/web/src", "22222222-2222-4222-8222-222222222222", "src", ThreadState.Idle, 9, 5, 42, sessionCwd = "/w/acme/web/src")
        val back = ThreadService.restored(ThreadService.saved(thread))!!
        assertEquals(thread.copy(state = ThreadState.NotStarted, stateSince = 0, anchor = null, sessionCwd = null), back)
        assertEquals("/w/acme/web/src", back.folder)
        assertNull(back.worktree)
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
