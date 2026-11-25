package io.github.v0rest.skein.threads

import io.github.v0rest.skein.paths.PathStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S1 = "11111111-1111-4111-8111-111111111111"
private const val S2 = "22222222-2222-4222-8222-222222222222"
private const val S3 = "33333333-3333-4333-8333-333333333333"

/** What a save writes while the saved threads wait, as in a project opened without trust, and once they are back. */
class SavedThreadsTest {
    private fun saved(id: String?, session: String?, worktree: String? = null, base: String? = null, pinned: Boolean = false, color: ThreadColor? = null) =
        ThreadService.SavedThread().apply {
            this.id = id
            folder = "/w/acme"
            sessionId = session
            name = "old $id"
            this.worktree = worktree
            this.base = base
            this.pinned = pinned
            this.color = color?.name
        }

    private fun live(id: String, session: String?, worktree: String? = null, base: String? = null, pinned: Boolean = false, color: ThreadColor? = null) =
        SkeinThread(
            id = id, folder = "/w/acme", sessionId = session, name = id, state = ThreadState.Idle, stateSince = 0, createdAt = 0, anchor = null,
            pinned = pinned, color = color, worktree = worktree, base = base,
        )

    private fun ids(threads: List<ThreadService.SavedThread>) = threads.map { it.id }

    /** With nothing started meanwhile, the file keeps what it had, as it is. */
    @Test
    fun whileTheyWaitWithNothingStartedTheLoadedListStays() {
        val loaded = listOf(saved("r1", S1), saved("r2", null), saved(null, S2))
        assertEquals(loaded, SavedThreads.toSave(restored = false, saved = loaded, live = emptyList()))
    }

    /**
     * A "+", New Thread Here or a resume from the History is the user's own act, allowed in Safe Mode: the waiting
     * threads stay in the file in their own order and the live ones come after them, so the restore opens the old ones
     * first, as it would have.
     */
    @Test
    fun whileTheyWaitTheLiveThreadsComeAfterTheWaitingOnes() {
        val loaded = listOf(saved("r1", S1), saved("r2", null))
        val written = SavedThreads.toSave(restored = false, saved = loaded, live = listOf(live("t1", S3), live("t2", null)))
        assertEquals(listOf("r1", "r2", "t1", "t2"), ids(written))
        assertEquals(S3, written[2].sessionId)
    }

    /** A waiting thread whose session the user resumed from the History meanwhile is written once, as the live thread. */
    @Test
    fun aWaitingThreadWhoseSessionALiveOneHoldsIsWrittenOnce() {
        val loaded = listOf(saved("r1", S1), saved("r2", S2))
        val written = SavedThreads.toSave(restored = false, saved = loaded, live = listOf(live("t1", S1)))
        assertEquals(listOf("r2", "t1"), ids(written))
        assertEquals(listOf(S2, S1), written.map { it.sessionId })
    }

    /**
     * Between the restore's rows going out and its flag a save sees the restored threads live under their saved ids, a
     * thread without a session among them, and writes each once.
     */
    @Test
    fun aWaitingThreadAlreadyBackUnderItsIdIsWrittenOnce() {
        val loaded = listOf(saved("r1", S1), saved("r2", null))
        val written = SavedThreads.toSave(restored = false, saved = loaded, live = listOf(live("r1", S1), live("r2", null)))
        assertEquals(listOf("r1", "r2"), ids(written))
    }

    /** Once the saved threads are back only the live ones count - a thread closed since is gone from the file. */
    @Test
    fun onceBackOnlyTheLiveThreadsAreWritten() {
        val loaded = listOf(saved("r1", S1), saved("r2", S2))
        assertEquals(listOf("t1"), ids(SavedThreads.toSave(restored = true, saved = loaded, live = listOf(live("t1", S3)))))
    }

    @Test
    fun noSessionIsHeldByNobody() {
        assertTrue(SavedThreads.heldByLive(S1, setOf(S1)))
        assertFalse(SavedThreads.heldByLive(S2, setOf(S1)))
        assertFalse(SavedThreads.heldByLive(null, setOf(S1)))
    }

    /** The saved threads still waiting are those no live thread is now, by its id or by its session; once back there are none. */
    @Test
    fun theWaitingOnesAreThoseNoLiveThreadIsNow() {
        val loaded = listOf(saved("r1", S1), saved("r2", S2), saved("r3", null))
        val live = listOf(live("r2", S2), live("t1", S1))
        assertEquals(listOf("r3"), ids(SavedThreads.waiting(restored = false, saved = loaded, live = live)))
        assertEquals(emptyList<String?>(), ids(SavedThreads.waiting(restored = true, saved = loaded, live = emptyList())))
    }

    /**
     * A thread the user resumed from the History on a waiting thread's session takes on what they gave the waiting one -
     * its pin, its colour and, in the same worktree, the base its changes count against - so the save loses none of it.
     */
    @Test
    fun aLiveThreadOnAWaitingSessionKeepsThatThreadsPinColourAndBaseInTheSave() {
        val loaded = listOf(saved("r1", S1, worktree = "feat-a", base = "main", pinned = true, color = ThreadColor.GREEN), saved("r2", S2))
        val written = SavedThreads.toSave(restored = false, saved = loaded, live = listOf(live("t1", S1, worktree = "feat-a")))
        assertEquals(listOf("r2", "t1"), ids(written))
        assertTrue(written[1].pinned)
        assertEquals("GREEN", written[1].color)
        assertEquals("main", written[1].base)
        assertEquals("feat-a", written[1].worktree)
    }

    /** What the live thread has of its own stays, and a base counts only in the same worktree. */
    @Test
    fun theLiveThreadsOwnMarksStayAndABaseNeedsTheSameWorktree() {
        val waiting = ThreadService.restored(saved("r1", S1, worktree = "feat-a", base = "main", pinned = true, color = ThreadColor.GREEN))!!
        val other = SavedThreads.merged(live("t1", S1, worktree = "feat-b", color = ThreadColor.RED), waiting)
        assertTrue(other.pinned)
        assertEquals(ThreadColor.RED, other.color)
        assertNull(other.base)
        assertNull(SavedThreads.merged(live("t2", S1), waiting).base)
        assertEquals("dev", SavedThreads.merged(live("t3", S1, worktree = "feat-a", base = "dev"), waiting).base)
        assertEquals("main", SavedThreads.merged(live("t4", S1, worktree = "feat-a"), waiting).base)
    }

    /** Once the saved threads are back a live thread is written as it is, nothing taken on from the file. */
    @Test
    fun onceBackALiveThreadTakesOnNothing() {
        val loaded = listOf(saved("r1", S1, pinned = true, color = ThreadColor.GREEN))
        val written = SavedThreads.toSave(restored = true, saved = loaded, live = listOf(live("t1", S1)))
        assertFalse(written.single().pinned)
        assertNull(written.single().color)
    }

    /**
     * The worktree checks of a close, of the Worktrees section and of a new worktree thread see the waiting threads, apart
     * from the live ones, as threads not started yet; a saved entry without an id never comes back, so it counts for
     * nothing. Once back, none waits.
     */
    @Test
    fun theWaitingThreadsAreTheSavedOnesNoLiveThreadIsYet() {
        val loaded = listOf(saved("r1", S1, worktree = "feat-a", base = "main"), saved("r2", S2), saved(null, S3))
        val waiting = SavedThreads.waitingThreads(restored = false, saved = loaded, live = listOf(live("t1", S2))).textOnly
        assertEquals(listOf("r1"), waiting.map { it.id })
        assertEquals(ThreadState.NotStarted, waiting.single().state)
        assertEquals("feat-a", waiting.single().worktree)
        assertEquals("main", waiting.single().base)
        assertEquals(0, SavedThreads.waitingThreads(restored = true, saved = loaded, live = listOf(live("t1", S2))).size)
    }

    /**
     * A worktree name the restore would not take - a hand-edited workspace file may hold any text there - names no
     * worktree while its thread waits either, and the base goes with it.
     */
    @Test
    fun aWorktreeNameTheRestoreWouldDropNamesNoWorktreeWhileItsThreadWaits() {
        fun waiting(worktree: String, style: PathStyle) =
            SavedThreads.waitingThreads(false, listOf(saved("r1", S1, worktree = worktree, base = "main")), emptyList(), style).textOnly.single()
        for (name in listOf("..", "../acme", "a/b", "-x", "\\\\srv\\share", "x".repeat(65))) {
            assertNull(name, waiting(name, PathStyle.UNIX).worktree)
            assertNull(name, waiting(name, PathStyle.UNIX).base)
        }
        // on Windows a name Win32 would trim is no name either
        assertNull(waiting("task-1.", PathStyle.WINDOWS).worktree)
        assertEquals("task-1", waiting("task-1", PathStyle.WINDOWS).worktree)
        assertEquals("main", waiting("task-1", PathStyle.WINDOWS).base)
    }
}
