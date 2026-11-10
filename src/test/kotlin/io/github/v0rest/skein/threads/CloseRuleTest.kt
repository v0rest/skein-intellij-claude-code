package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Closing a thread's tab ends its claude at once, so Close asks first while claude may be in the middle of something. */
class CloseRuleTest {
    @Test
    fun claudeIsMidTurnWhileStartingWorkingInTheBackgroundOrWaitingForTheUser() {
        assertEquals(CloseRule.Busy.WORKING, CloseRule.busy(ThreadState.Starting))
        assertEquals(CloseRule.Busy.WORKING, CloseRule.busy(ThreadState.Working))
        assertEquals(CloseRule.Busy.WORKING, CloseRule.busy(ThreadState.WorkingUnknown))
        assertEquals(CloseRule.Busy.WORKING, CloseRule.busy(ThreadState.Background))
        assertEquals(CloseRule.Busy.WORKING, CloseRule.busy(ThreadState.NeedsYou(WaitReason.PERMISSION, since = 1)))
    }

    /** Unknown is set while claude may still run in the tab: a status file Skein cannot read, a launch not matched yet. */
    @Test
    fun anUnknownStateMayStillBeWorking() {
        assertEquals(CloseRule.Busy.MAYBE, CloseRule.busy(ThreadState.Unknown))
    }

    @Test
    fun aThreadBetweenTurnsOrWithoutItsClaudeClosesWithoutAQuestion() {
        assertNull(CloseRule.busy(ThreadState.Idle))
        assertNull(CloseRule.busy(ThreadState.YourTurn(since = 1)))
        assertNull(CloseRule.busy(ThreadState.NotStarted))
        assertNull(CloseRule.busy(ThreadState.Exited))
        assertNull(CloseRule.busy(ThreadState.RunningElsewhere(pid = 4242)))
        assertFalse(CloseRule.isBusy(ThreadState.Idle))
    }

    @Test
    fun theBalloonNamesTheThreadsLeftOpen() {
        assertEquals("acme started working, so Skein left it open", CloseRule.leftOpenText(listOf("acme")))
        assertEquals("acme, beta started working, so Skein left them open", CloseRule.leftOpenText(listOf("acme", "beta")))
    }
}
