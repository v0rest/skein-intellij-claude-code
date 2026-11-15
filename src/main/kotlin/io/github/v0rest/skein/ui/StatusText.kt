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
            WaitReason.TRUST -> TRUST_QUESTION
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
        state is ThreadState.NeedsYou && state.reason == WaitReason.TRUST -> TRUST_QUESTION
        else -> line(state)
    }

    /**
     * A sentence the tooltip adds under [tooltipLine], or null. Only the trust wait has one - a Claude home Skein does not
     * see (CLAUDE_CONFIG_DIR set only in the terminal's settings) would keep a thread on that question for good.
     */
    fun tooltipHint(state: ThreadState): String? =
        if (state is ThreadState.NeedsYou && state.reason == WaitReason.TRUST) TRUST_HINT else null

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

    /** Said in full on the row too: it is what the person has to answer in the tab, and no other row says it. */
    private const val TRUST_QUESTION = "claude asks whether to trust this folder"

    private const val TRUST_HINT =
        "If claude is past that question already, Skein may be reading another Claude home than the terminal's - check CLAUDE_CONFIG_DIR."

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
