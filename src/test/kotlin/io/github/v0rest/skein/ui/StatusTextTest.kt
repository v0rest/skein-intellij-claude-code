package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {
    @Test
    fun lines() {
        assertEquals("permission", StatusText.line(ThreadState.NeedsYou(WaitReason.PERMISSION, 0)))
        assertEquals("question", StatusText.line(ThreadState.NeedsYou(WaitReason.QUESTION, 0)))
        assertEquals("needs you", StatusText.line(ThreadState.NeedsYou(WaitReason.OTHER, 0)))
        assertEquals("your turn", StatusText.line(ThreadState.YourTurn(0)))
        assertEquals("working", StatusText.line(ThreadState.Working))
        assertEquals("working", StatusText.line(ThreadState.WorkingUnknown))
        assertEquals("background task", StatusText.line(ThreadState.Background))
        assertEquals("idle", StatusText.line(ThreadState.Idle))
        assertEquals("starting", StatusText.line(ThreadState.Starting))
        assertEquals("not started", StatusText.line(ThreadState.NotStarted))
        assertEquals("running elsewhere", StatusText.line(ThreadState.RunningElsewhere(1)))
        assertEquals("exited", StatusText.line(ThreadState.Exited))
        assertEquals("status unknown", StatusText.line(ThreadState.Unknown))
    }

    @Test
    fun ages() {
        assertEquals("now", StatusText.age(0, 59_000))
        assertEquals("1 min", StatusText.age(0, 60_000))
        assertEquals("4 min", StatusText.age(0, 4 * 60_000))
        assertEquals("2 h", StatusText.age(0, 2 * 3_600_000))
        assertEquals("3 d", StatusText.age(0, 3 * 86_400_000L))
        assertEquals("now", StatusText.age(5_000, 1_000))
    }

    /** claude asks whether to trust the folder before it writes any status: the row and its tooltip say so in full. */
    @Test
    fun theTrustQuestionIsSaidInFull() {
        val asking = ThreadState.NeedsYou(WaitReason.TRUST, 0)
        assertEquals("claude asks whether to trust this folder", StatusText.line(asking))
        assertEquals("claude asks whether to trust this folder", StatusText.tooltipLine(asking))
    }
}
