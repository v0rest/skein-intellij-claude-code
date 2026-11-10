package io.github.v0rest.skein.power

import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.claude.WaitReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAwakePolicyTest {
    private val minute = 60_000L
    private val now = 10_000_000L

    private fun thread(state: ThreadState, since: Long = now, id: String = "t1") = SkeinThread(
        id = id,
        folder = "/w/acme",
        sessionId = "55555555-5555-4555-8555-555555555555",
        name = "thread",
        state = state,
        stateSince = since,
        createdAt = 0,
        anchor = null,
    )

    @Test fun workingThreadsHold() {
        for (state in listOf(ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Starting)) {
            assertTrue("$state", KeepAwakePolicy.holds(listOf(thread(state)), now, 30))
        }
    }

    @Test fun waitingAndIdleThreadsDoNotHold() {
        val states = listOf(ThreadState.NeedsYou(WaitReason.PERMISSION, now), ThreadState.YourTurn(now), ThreadState.RunningElsewhere(1), ThreadState.Idle, ThreadState.NotStarted, ThreadState.Exited, ThreadState.Unknown)
        for (state in states) {
            assertFalse("$state", KeepAwakePolicy.holds(listOf(thread(state)), now, 30))
        }
        assertFalse(KeepAwakePolicy.holds(emptyList(), now, 30))
    }

    @Test fun backgroundHoldsForTheChosenMinutesAfterItBegan() {
        val began = now - 29 * minute
        assertTrue(KeepAwakePolicy.holds(listOf(thread(ThreadState.Background, began)), now, 30))
        assertFalse(KeepAwakePolicy.holds(listOf(thread(ThreadState.Background, now - 31 * minute)), now, 30))
    }

    @Test fun backgroundNeverHoldsWhenSetToNoAndAlwaysWhenSetToAsLongAsItRuns() {
        val old = thread(ThreadState.Background, now - 100_000 * minute)
        assertFalse(KeepAwakePolicy.holds(listOf(thread(ThreadState.Background)), now, 0))
        assertTrue(KeepAwakePolicy.holds(listOf(old), now, -1))
    }

    @Test fun backgroundSetToNoDoesNotHoldWhenTheClockStepsBack() {
        assertFalse(KeepAwakePolicy.holds(listOf(thread(ThreadState.Background, now + minute)), now, 0))
    }

    @Test fun anyOpenProjectsWorkingThreadHolds() {
        val projectA = listOf(thread(ThreadState.Idle, id = "a"))
        val projectB = listOf(thread(ThreadState.Working, id = "b"))
        assertTrue(KeepAwakePolicy.holds(projectA + projectB, now, 30))
        assertFalse(KeepAwakePolicy.holds(projectA, now, 30))
    }
}
