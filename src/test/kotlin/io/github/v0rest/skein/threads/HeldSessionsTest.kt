package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldSessionsTest {
    private fun thread(id: String, session: String?) =
        SkeinThread(id = id, folder = "/p", sessionId = session, name = id, state = ThreadState.Idle, stateSince = 0, createdAt = 0, anchor = null)

    @Test
    fun beforeTheSavedThreadsAreBackTheyHoldTheirSessionsToo() {
        assertEquals(setOf("saved-1", "saved-2"), HeldSessions.of(restored = false, live = emptyList(), saved = listOf("saved-1", null, "saved-2")))
    }

    @Test
    fun onceRestoredOnlyTheLiveThreadsCount() {
        // a thread closed since the start no longer holds its session, though the loaded state still lists it
        val live = listOf(thread("t1", "kept"), thread("t2", null))
        assertEquals(setOf("kept"), HeldSessions.of(restored = true, live = live, saved = listOf("kept", "closed-since")))
    }
}
