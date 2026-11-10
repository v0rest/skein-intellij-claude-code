package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMarksTest {
    private val a = "55555555-5555-4555-8555-555555555555"
    private val b = "66666666-6666-4666-8666-666666666666"
    private val c = "77777777-7777-4777-8777-777777777777"

    @Test fun renameSetsAndABlankNameClears() {
        val named = SessionMarks().rename(a, "Fix login")
        assertEquals(mapOf(a to "Fix login"), named.names)
        assertEquals(emptyMap<String, String>(), named.rename(a, "  ").names)
        assertEquals(emptyMap<String, String>(), named.rename(a, null).names)
    }

    @Test fun markDoneRemembersWhenAndUndoneForgets() {
        val done = SessionMarks().markDone(listOf(a, b), 42L)
        assertEquals(mapOf(a to 42L, b to 42L), done.done)
        assertEquals(mapOf(b to 42L), done.undone(listOf(a)).done)
    }

    @Test fun pinAddsAndRemoves() {
        val pinned = SessionMarks().pin(listOf(a, b), true)
        assertEquals(setOf(a, b), pinned.pinned)
        assertEquals(setOf(b), pinned.pin(listOf(a), false).pinned)
    }

    @Test fun followMovesMarksToTheSessionThatNowStandsForTheConversation() {
        val marks = SessionMarks(pinned = setOf(a), names = mapOf(a to "Old", b to "Own"), done = mapOf(a to 7L))
        val followed = marks.follow(b, listOf(a))
        assertEquals(setOf(b), followed.pinned)
        assertEquals(mapOf(b to "Own"), followed.names)
        assertEquals(mapOf(b to 7L), followed.done)
    }

    @Test fun pruneDropsMarksOfSessionsNoLongerFound() {
        val marks = SessionMarks(setOf(a, b), mapOf(a to "A", b to "B"), mapOf(a to 1L, b to 2L))
        assertEquals(SessionMarks(setOf(a), mapOf(a to "A"), mapOf(a to 1L)), marks.prune(setOf(a), emptySet(), examined = marks.ids()))
    }

    @Test fun pruneLeavesAloneTheMarksItDidNotExamine() {
        // a mark added after the scan sampled the store is not pruned until a scan has looked at it
        val marks = SessionMarks(setOf(a, b), mapOf(c to "C"), mapOf(b to 2L))
        val pruned = marks.prune(emptySet(), emptySet(), examined = setOf(a, b))
        assertEquals(SessionMarks(emptySet(), mapOf(c to "C"), emptyMap()), pruned)
        assertEquals(setOf(a, b, c), marks.ids())
    }

    @Test fun pruneKeepsMarksOfSessionsThreadsHold() {
        val marks = SessionMarks(setOf(a, b), mapOf(b to "B"), mapOf(c to 3L))
        val pruned = marks.prune(emptySet(), setOf(b, c), examined = marks.ids())
        assertEquals(setOf(b), pruned.pinned)
        assertEquals(mapOf(b to "B"), pruned.names)
        assertEquals(mapOf(c to 3L), pruned.done)
        assertTrue(marks.prune(emptySet(), emptySet(), examined = marks.ids()) == SessionMarks())
    }
}
