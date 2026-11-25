package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What ThreadService does with a thread's colour in the session marks, without the IDE. */
class SessionColorsTest {
    private val a = "55555555-5555-4555-8555-555555555555"
    private val b = "66666666-6666-4666-8666-666666666666"

    private fun thread(id: String, sessionId: String?, color: ThreadColor? = null) =
        SkeinThread(id, "/w/acme", sessionId, "acme", ThreadState.Idle, 0, 0, null, color = color)

    @Test
    fun aColourSetOnThreadsGoesToTheirSessionsAndNoneClearsIt() {
        val threads = listOf(thread("t1", a), thread("t2", null), thread("t3", b))
        val red = SessionColors.set(SessionMarks(), threads, listOf("t1", "t2"), ThreadColor.RED)
        assertEquals("a thread without a session yet keeps it on its record alone", mapOf(a to ThreadColor.RED), red.colors)
        val both = SessionColors.set(red, threads, listOf("t3"), ThreadColor.BLUE)
        assertEquals(mapOf(a to ThreadColor.RED, b to ThreadColor.BLUE), both.colors)
        assertEquals(mapOf(b to ThreadColor.BLUE), SessionColors.set(both, threads, listOf("t1"), null).colors)
        assertSame("nothing to change", both, SessionColors.set(both, threads, listOf("t2", "gone"), null))
    }

    /** A thread coloured before its claude started - its session gets the colour once the thread learns it. */
    @Test
    fun aColourSetBeforeTheSessionIdMovesIntoTheMarksWhenTheIdAppears() {
        val before = mapOf("t1" to null)
        val now = listOf(thread("t1", a, ThreadColor.GREEN))
        assertEquals(mapOf(a to ThreadColor.GREEN), SessionColors.fill(SessionMarks(), before, now).colors)
        // still without a session, or without a colour - nothing to remember
        assertEquals(SessionMarks(), SessionColors.fill(SessionMarks(), before, listOf(thread("t1", null, ThreadColor.GREEN))))
        assertEquals(SessionMarks(), SessionColors.fill(SessionMarks(), before, listOf(thread("t1", a))))
    }

    /**
     * Only the moment a thread gets a session, or another one, counts. A thread that already had it at the last look
     * adds nothing, so within a run a colour History moved on to a later part of the conversation ([SessionMarks.follow])
     * does not come back.
     */
    @Test
    fun aThreadThatAlreadyHadItsSessionAddsNothing() {
        val now = listOf(thread("t1", a, ThreadColor.GREEN))
        val marks = SessionMarks(colors = mapOf(b to ThreadColor.GREEN))
        assertSame(marks, SessionColors.fill(marks, mapOf("t1" to a), now))
        // a thread that moved from b to a gives a its colour, since a has none
        assertEquals(mapOf(a to ThreadColor.GREEN, b to ThreadColor.GREEN), SessionColors.fill(marks, mapOf("t1" to b), now).colors)
    }

    /**
     * A coloured thread's session changes (`/clear`, a resume under a new id) - ThreadService carries the marks first
     * and fills after. The old session's mark moves on when it has one, the thread's own colour goes when it has none
     * (History moved it on, or it never got there), and a new session with a colour of its own keeps it.
     */
    @Test
    fun aThreadWhoseSessionChangedGivesTheNewOneItsColourUnlessTheMarksAlreadyDid() {
        val c = "77777777-7777-4777-8777-777777777777"
        val before = mapOf("t1" to a)
        val after = mapOf("t1" to c)
        val now = listOf(thread("t1", c, ThreadColor.RED))
        val step = { m: SessionMarks -> SessionColors.fill(m.carry(before, after), before, now) }
        assertEquals("the old session has no mark", mapOf(c to ThreadColor.RED), step(SessionMarks()).colors)
        assertEquals(
            "the old session's mark is carried, and it wins",
            mapOf(a to ThreadColor.BLUE, c to ThreadColor.BLUE),
            step(SessionMarks(colors = mapOf(a to ThreadColor.BLUE))).colors,
        )
        assertEquals(
            "the new session keeps its own",
            mapOf(c to ThreadColor.PURPLE),
            step(SessionMarks(colors = mapOf(c to ThreadColor.PURPLE))).colors,
        )
        // an uncoloured thread gives nothing
        assertEquals(SessionMarks(), SessionColors.fill(SessionMarks(), before, listOf(thread("t1", c))))
    }

    /**
     * A thread restored from the workspace file is new at the first look after the restore, so the colour it was saved
     * with becomes its session's, and colours set before they went with the session are not lost on the next close. A
     * session that has a colour already keeps it, and of two restored threads on one session the first decides. This
     * runs at every IDE start, not once - see the next test.
     */
    @Test
    fun aRestoredThreadsColourBecomesItsSessionsUnlessTheSessionHasOne() {
        val restored = listOf(thread("r1", a, ThreadColor.ORANGE), thread("r2", b, ThreadColor.YELLOW), thread("r3", b, ThreadColor.BLUE))
        assertEquals(mapOf(a to ThreadColor.ORANGE, b to ThreadColor.YELLOW), SessionColors.fill(SessionMarks(), emptyMap(), restored).colors)
        val own = SessionMarks(colors = mapOf(a to ThreadColor.PURPLE))
        assertEquals(mapOf(a to ThreadColor.PURPLE, b to ThreadColor.YELLOW), SessionColors.fill(own, emptyMap(), restored).colors)
        val all = SessionMarks(colors = mapOf(a to ThreadColor.PURPLE, b to ThreadColor.RED))
        assertSame(all, SessionColors.fill(all, emptyMap(), restored))
    }

    /**
     * The service starts every IDE run with no last look, so each restored thread is new then. A colour History moved
     * from the thread's session to a later part of the conversation comes back to that session after a restart, until
     * the next History scan moves it on again, while a session with a colour of its own is never overridden.
     */
    @Test
    fun afterARestartARestoredThreadsColourComesBackToItsSessionButNeverOverOne() {
        val now = listOf(thread("t1", a, ThreadColor.GREEN))
        // History moved a's colour on to b, its later part - within the run it stays there
        val moved = SessionMarks(colors = mapOf(b to ThreadColor.GREEN))
        assertSame(moved, SessionColors.fill(moved, mapOf("t1" to a), now))
        assertEquals(mapOf(a to ThreadColor.GREEN, b to ThreadColor.GREEN), SessionColors.fill(moved, emptyMap(), now).colors)
        val own = SessionMarks(colors = mapOf(a to ThreadColor.RED))
        assertSame(own, SessionColors.fill(own, emptyMap(), now))
    }
}
