package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A thread waiting to be opened (a restored one, the neighbour left in front after a close) whose tab stays in view a
 * moment, its shell started, counts as opened - the way out of a bare shell the user looks at and nothing they do there
 * starts. The moment runs on the clock the coordinator is given, not on how many looks there were.
 */
class InViewStartTest {
    private val dwell = InViewStart.DWELL_MILLIS

    private fun starts(state: ThreadState = ThreadState.NotStarted, since: Long? = 1_000, now: Long = 1_000 + dwell, running: Boolean = true, typed: Boolean = false) =
        InViewStart.starts(state, since, now, { typed }, { running })

    @Test
    fun aTabComingIntoViewStartsItsMomentNow() = assertEquals(2_000L, InViewStart.inViewSince(null, inView = true, now = 2_000))

    @Test
    fun aTabStayingInViewKeepsItsStart() = assertEquals(1_000L, InViewStart.inViewSince(1_000, inView = true, now = 9_000))

    @Test
    fun aTabOutOfViewHasNoMoment() = assertNull(InViewStart.inViewSince(1_000, inView = false, now = 9_000))

    /** A clock that went back cannot leave a moment that never ends - it starts again from now. */
    @Test
    fun aClockThatWentBackStartsTheMomentAgain() = assertEquals(500L, InViewStart.inViewSince(1_000, inView = true, now = 500))

    @Test
    fun aNotStartedThreadInViewForTheMomentWithItsShellStarts() = assertTrue(starts())

    @Test
    fun aShorterLookDoesNot() = assertFalse(starts(now = 1_000 + dwell - 1))

    @Test
    fun aTabOutOfViewDoesNot() = assertFalse(starts(since = null))

    /**
     * Typing into a tab whose shell has not started waits for it, and one whose shell exited never takes the command -
     * either way the launch slot of every window would wait.
     */
    @Test
    fun aTabWithoutARunningShellDoesNot() = assertFalse(starts(running = false))

    /**
     * A shell the user typed into may hold a line of theirs at the prompt or run a program of theirs, and a command typed
     * there would join it - such a tab waits for the user to open the thread.
     */
    @Test
    fun aTabTheUserTypedIntoDoesNot() = assertFalse(starts(typed = true))

    @Test
    fun onlyANotStartedThreadStarts() {
        val others = listOf(
            ThreadState.Starting, ThreadState.Idle, ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background,
            ThreadState.YourTurn(0), ThreadState.NeedsYou(WaitReason.TRUST, 0), ThreadState.Exited, ThreadState.Unknown,
            ThreadState.RunningElsewhere(200),
        )
        for (state in others) assertFalse(state.toString(), starts(state = state))
    }

    /**
     * The terminal is asked only once the rest holds, so a tab out of view or of another state costs no look at it, and
     * about the shell last, once nobody typed into the tab.
     */
    @Test
    fun theTerminalIsAskedLast() {
        val asked = mutableListOf<String>()
        val typed = { asked += "typed"; false }
        val running = { asked += "running"; true }
        InViewStart.starts(ThreadState.Idle, 1_000, 1_000 + dwell, typed, running)
        InViewStart.starts(ThreadState.NotStarted, null, 1_000 + dwell, typed, running)
        InViewStart.starts(ThreadState.NotStarted, 1_000, 1_000, typed, running)
        assertEquals(emptyList<String>(), asked)
        InViewStart.starts(ThreadState.NotStarted, 1_000, 1_000 + dwell, { asked += "typed"; true }, running)
        assertEquals(listOf("typed"), asked)
        assertTrue(InViewStart.starts(ThreadState.NotStarted, 1_000, 1_000 + dwell, typed, running))
        assertEquals(listOf("typed", "typed", "running"), asked)
    }
}
