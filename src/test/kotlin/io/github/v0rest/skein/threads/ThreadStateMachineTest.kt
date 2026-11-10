package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity.BACKGROUND
import io.github.v0rest.skein.claude.ClaudeActivity.BUSY
import io.github.v0rest.skein.claude.ClaudeActivity.IDLE
import io.github.v0rest.skein.claude.ClaudeActivity.UNKNOWN
import io.github.v0rest.skein.claude.ClaudeActivity.WAITING
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.testutil.sessionFile
import io.github.v0rest.skein.threads.ThreadState.Background
import io.github.v0rest.skein.threads.ThreadState.Exited
import io.github.v0rest.skein.threads.ThreadState.Idle
import io.github.v0rest.skein.threads.ThreadState.NeedsYou
import io.github.v0rest.skein.threads.ThreadState.NotStarted
import io.github.v0rest.skein.threads.ThreadState.RunningElsewhere
import io.github.v0rest.skein.threads.ThreadState.Starting
import io.github.v0rest.skein.threads.ThreadState.Unknown
import io.github.v0rest.skein.threads.ThreadState.Working
import io.github.v0rest.skein.threads.ThreadState.WorkingUnknown
import io.github.v0rest.skein.threads.ThreadState.YourTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadStateMachineTest {
    private val permission = sessionFile(activity = WAITING, waitingFor = "permission prompt")
    private val question = sessionFile(activity = WAITING, waitingFor = "input needed")
    private val idle = sessionFile(activity = IDLE)
    private val busy = sessionFile(activity = BUSY)

    @Test
    fun waitingNotViewedNotifies() =
        assertEquals(Transition(NeedsYou(WaitReason.PERMISSION, 10), NotificationKind.NEEDS_YOU), ThreadStateMachine.onSession(Working, permission, viewed = false, now = 10))

    @Test
    fun waitingViewedDoesNotNotify() =
        assertEquals(Transition(NeedsYou(WaitReason.PERMISSION, 10), null), ThreadStateMachine.onSession(Working, permission, viewed = true, now = 10))

    @Test
    fun waitingFromIdleNotifiesToo() =
        assertEquals(Transition(NeedsYou(WaitReason.QUESTION, 10), NotificationKind.NEEDS_YOU), ThreadStateMachine.onSession(Idle, question, viewed = false, now = 10))

    @Test
    fun stillWaitingKeepsSince() =
        assertEquals(Transition(NeedsYou(WaitReason.PERMISSION, 10), null), ThreadStateMachine.onSession(NeedsYou(WaitReason.PERMISSION, 10), permission, viewed = false, now = 20))

    @Test
    fun waitingReasonChangeIsANewStop() =
        assertEquals(Transition(NeedsYou(WaitReason.PERMISSION, 20), NotificationKind.NEEDS_YOU), ThreadStateMachine.onSession(NeedsYou(WaitReason.QUESTION, 10), permission, viewed = false, now = 20))

    @Test
    fun turnFinishedNotViewed() =
        assertEquals(Transition(YourTurn(30), NotificationKind.TURN_FINISHED), ThreadStateMachine.onSession(Working, idle, viewed = false, now = 30))

    @Test
    fun turnFinishedViewed() =
        assertEquals(Transition(Idle, null), ThreadStateMachine.onSession(Working, idle, viewed = true, now = 30))

    @Test
    fun answeredThenIdleCountsAsTurn() =
        assertEquals(Transition(YourTurn(30), NotificationKind.TURN_FINISHED), ThreadStateMachine.onSession(NeedsYou(WaitReason.PERMISSION, 10), idle, viewed = false, now = 30))

    @Test
    fun freshStartIsQuietIdle() {
        for (prev in listOf(Starting, NotStarted, Unknown, Exited, RunningElsewhere(7))) {
            assertEquals("from $prev", Transition(Idle, null), ThreadStateMachine.onSession(prev, idle, viewed = false, now = 30))
        }
    }

    @Test
    fun idleStaysIdleQuietly() =
        assertEquals(Transition(Idle, null), ThreadStateMachine.onSession(Idle, idle, viewed = false, now = 30))

    @Test
    fun yourTurnStays() =
        assertEquals(Transition(YourTurn(30), null), ThreadStateMachine.onSession(YourTurn(30), idle, viewed = false, now = 40))

    @Test
    fun busyIsWorking() {
        for (prev in listOf(Idle, Working, YourTurn(1), NeedsYou(WaitReason.OTHER, 1), Unknown)) {
            assertEquals("from $prev", Transition(Working, null), ThreadStateMachine.onSession(prev, busy, viewed = false, now = 30))
        }
    }

    @Test
    fun busyDuringStartupStaysStartingAndFirstIdleIsQuiet() {
        for (prev in listOf(Starting, NotStarted, Exited, RunningElsewhere(7))) {
            assertEquals("from $prev", Transition(Starting, null), ThreadStateMachine.onSession(prev, busy, viewed = false, now = 30))
        }
        assertEquals(Transition(Idle, null), ThreadStateMachine.onSession(Starting, idle, viewed = false, now = 31))
    }

    @Test
    fun unknownActivityIsWorkingSilently() {
        val unknown = sessionFile(activity = UNKNOWN)
        for (prev in listOf(Idle, Working, YourTurn(5), NeedsYou(WaitReason.OTHER, 1), Unknown, WorkingUnknown)) {
            assertEquals("from $prev", Transition(WorkingUnknown, null), ThreadStateMachine.onSession(prev, unknown, viewed = false, now = 30))
        }
        assertEquals(Transition(Starting, null), ThreadStateMachine.onSession(Starting, unknown, viewed = false, now = 30))
    }

    @Test
    fun unknownThenIdleNeverAnnouncesATurn() {
        assertEquals(Transition(Idle, null), ThreadStateMachine.onSession(WorkingUnknown, idle, viewed = false, now = 31))
        assertEquals(Transition(Working, null), ThreadStateMachine.onSession(WorkingUnknown, busy, viewed = false, now = 31))
        assertEquals(Transition(NeedsYou(WaitReason.PERMISSION, 31), NotificationKind.NEEDS_YOU), ThreadStateMachine.onSession(WorkingUnknown, permission, viewed = false, now = 31))
    }

    private val background = sessionFile(activity = BACKGROUND)

    @Test
    fun aBackgroundShellTaskIsNotAFinishedTurn() {
        // the owner's own log: busy -> shell -> busy -> shell ... while claude waits on its background builds
        var state: ThreadState = Idle
        val notified = mutableListOf<NotificationKind>()
        for ((i, file) in listOf(busy, background, busy, background, busy, idle).withIndex()) {
            val t = ThreadStateMachine.onSession(state, file, viewed = false, now = 10L + i)
            state = t.state
            t.notify?.let { notified += it }
        }
        assertEquals(listOf(NotificationKind.TURN_FINISHED), notified)
        assertEquals(YourTurn(15), state)
    }

    @Test
    fun backgroundWorkShowsAsBackgroundAndWaitsStillNotify() {
        assertEquals(Transition(Background, null), ThreadStateMachine.onSession(Working, background, viewed = false, now = 1))
        assertEquals(Transition(Background, null), ThreadStateMachine.onSession(YourTurn(0), background, viewed = false, now = 1))
        assertEquals(Transition(NeedsYou(WaitReason.PERMISSION, 2), NotificationKind.NEEDS_YOU), ThreadStateMachine.onSession(Background, permission, viewed = false, now = 2))
        assertEquals(Transition(Working, null), ThreadStateMachine.onSession(Background, busy, viewed = false, now = 3))
        assertEquals(Transition(Starting, null), ThreadStateMachine.onSession(Starting, background, viewed = false, now = 4))
    }

    @Test
    fun yourTurnClearsWhenTheUserIsLookingAtIt() =
        assertEquals(Transition(Idle, null), ThreadStateMachine.onSession(YourTurn(30), idle, viewed = true, now = 40))

    @Test
    fun viewingClearsYourTurnOnly() {
        assertEquals(Idle, ThreadStateMachine.onViewed(YourTurn(30)))
        assertEquals(NeedsYou(WaitReason.PERMISSION, 10), ThreadStateMachine.onViewed(NeedsYou(WaitReason.PERMISSION, 10)))
        assertEquals(Working, ThreadStateMachine.onViewed(Working))
    }
}
