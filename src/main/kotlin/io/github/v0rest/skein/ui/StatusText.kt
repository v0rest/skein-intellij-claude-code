package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.ThreadState

/** The short words a thread row shows. */
object StatusText {
    fun line(state: ThreadState): String = when (state) {
        is ThreadState.NeedsYou -> when (state.reason) {
            WaitReason.PERMISSION -> "permission"
            WaitReason.QUESTION -> "question"
            WaitReason.OTHER -> "needs you"
        }
        is ThreadState.YourTurn -> "your turn"
        ThreadState.Working, ThreadState.WorkingUnknown -> "working"
        ThreadState.Background -> "background task"
        ThreadState.Idle -> "idle"
        ThreadState.Starting -> "starting"
        ThreadState.NotStarted -> "not started"
        is ThreadState.RunningElsewhere -> "running elsewhere"
        ThreadState.Exited -> "exited"
        ThreadState.Unknown -> "status unknown"
    }

    /** The words of a row's tooltip: like [line] but a waiting thread says what it waits for. */
    fun tooltipLine(state: ThreadState): String = when {
        state is ThreadState.NeedsYou && state.reason == WaitReason.PERMISSION -> "waiting for your permission"
        state is ThreadState.NeedsYou && state.reason == WaitReason.QUESTION -> "waiting for your answer"
        else -> line(state)
    }

    /** "now", "4 min", "2 h", "3 d": the time since [since], rounded down. */
    fun age(since: Long, now: Long): String {
        val elapsed = (now - since).coerceAtLeast(0)
        return when {
            elapsed < MINUTE -> "now"
            elapsed < HOUR -> "${elapsed / MINUTE} min"
            elapsed < DAY -> "${elapsed / HOUR} h"
            else -> "${elapsed / DAY} d"
        }
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
