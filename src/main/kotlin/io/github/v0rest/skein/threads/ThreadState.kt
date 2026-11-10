package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.claude.waitReasonOf

/** What a thread row shows. The state machine below derives the first four from status files; the coordinator sets the rest. */
sealed interface ThreadState {
    /** `claude` is blocked on a dialog since [since] (epoch millis); orange, top of the list. */
    data class NeedsYou(val reason: WaitReason, val since: Long) : ThreadState

    /** A turn finished at [since] and the user has not looked at the tab yet; blue, right after the orange rows. */
    data class YourTurn(val since: Long) : ThreadState

    data object Working : ThreadState

    /** `claude` reports a status value Skein does not know; shown as working, and its next idle is not a finished turn. */
    data object WorkingUnknown : ThreadState

    /** The turn ended but claude's own background shell task still runs; claude carries on when it finishes. */
    data object Background : ThreadState

    data object Idle : ThreadState

    /** `claude` was typed into the tab and its first idle has not been seen yet. */
    data object Starting : ThreadState

    /** Restored after an IDE restart; `claude --resume` runs when the tab is first opened. */
    data object NotStarted : ThreadState

    /** The thread's session is already running in another terminal, under [pid]. */
    data class RunningElsewhere(val pid: Long) : ThreadState

    /** `claude` exited; the shell is still there. */
    data object Exited : ThreadState

    /** No status Skein trusts. Never a guess. */
    data object Unknown : ThreadState
}

enum class NotificationKind { NEEDS_YOU, TURN_FINISHED }

data class Transition(val state: ThreadState, val notify: NotificationKind?)

object ThreadStateMachine {
    /** The next state after a status-file read; [viewed] says whether the user is looking at the thread's tab right now. */
    fun onSession(prev: ThreadState, file: SessionFile, viewed: Boolean, now: Long): Transition = when (file.activity) {
        ClaudeActivity.WAITING -> {
            val reason = waitReasonOf(file.waitingFor)
            if (prev is ThreadState.NeedsYou && prev.reason == reason) {
                Transition(prev, null)
            } else {
                Transition(ThreadState.NeedsYou(reason, now), if (viewed) null else NotificationKind.NEEDS_YOU)
            }
        }
        ClaudeActivity.BUSY -> Transition(if (prev.isStartingUp()) ThreadState.Starting else ThreadState.Working, null)
        // waiting on its own background task is still claude's work, not the user's turn
        ClaudeActivity.BACKGROUND -> Transition(if (prev.isStartingUp()) ThreadState.Starting else ThreadState.Background, null)
        // a value Skein does not know is shown as working but never leads to a notification, not even at the next idle
        ClaudeActivity.UNKNOWN -> Transition(if (prev.isStartingUp()) ThreadState.Starting else ThreadState.WorkingUnknown, null)
        ClaudeActivity.IDLE -> when (prev) {
            // the user is looking at it: the turn is read
            is ThreadState.YourTurn -> Transition(if (viewed) ThreadState.Idle else prev, null)
            ThreadState.Idle -> Transition(prev, null)
            ThreadState.Working, ThreadState.Background, is ThreadState.NeedsYou ->
                if (viewed) Transition(ThreadState.Idle, null) else Transition(ThreadState.YourTurn(now), NotificationKind.TURN_FINISHED)
            else -> Transition(ThreadState.Idle, null)
        }
    }

    /** The user looked at the tab: "your turn" is consumed, everything else stays. */
    fun onViewed(prev: ThreadState): ThreadState = if (prev is ThreadState.YourTurn) ThreadState.Idle else prev

    /** States in which a busy `claude` is still coming up (a slow `--resume`), so its first idle is not a finished turn. */
    private fun ThreadState.isStartingUp(): Boolean =
        this == ThreadState.Starting || this == ThreadState.NotStarted || this == ThreadState.Exited || this is ThreadState.RunningElsewhere
}

object ThreadOrdering {
    /** Pinned rows first, then the rest; inside each group: needs-you rows (longest waiting first), then your-turn rows (same), then everything else in creation order. */
    fun <T> sort(items: List<T>, state: (T) -> ThreadState, createdAt: (T) -> Long, pinned: (T) -> Boolean = { false }): List<T> =
        byState(items.filter(pinned), state, createdAt) + byState(items.filterNot(pinned), state, createdAt)

    private fun <T> byState(items: List<T>, state: (T) -> ThreadState, createdAt: (T) -> Long): List<T> {
        val needsYou = items.filter { state(it) is ThreadState.NeedsYou }.sortedBy { (state(it) as ThreadState.NeedsYou).since }
        val yourTurn = items.filter { state(it) is ThreadState.YourTurn }.sortedBy { (state(it) as ThreadState.YourTurn).since }
        val rest = items.filter { state(it) !is ThreadState.NeedsYou && state(it) !is ThreadState.YourTurn }.sortedBy(createdAt)
        return needsYou + yourTurn + rest
    }
}
