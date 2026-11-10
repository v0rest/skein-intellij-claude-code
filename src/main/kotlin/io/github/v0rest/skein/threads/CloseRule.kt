package io.github.v0rest.skein.threads

/**
 * When closing a thread's tab could end a claude in the middle of something, so Close asks first. Close hangs up the
 * thread's claude right away ([CloseSequence]) and nothing in the IDE asks about a Skein editor tab, so the thread menu
 * asks for every busy thread, and right before the close the coordinator leaves open a thread that turned busy after
 * that question. The editor tab's own × asks nothing: closing it was the user's own act.
 */
object CloseRule {
    /** How sure Skein is that claude is mid-turn. */
    enum class Busy {
        /** claude is starting, working (on something Skein knows or not), waiting on its background task, or on the user. */
        WORKING,

        /**
         * The state is unknown, which Skein also sets while claude runs in the tab: a status file of the tab's own claude
         * it cannot read, or a launch not matched after its timeout.
         */
        MAYBE,
    }

    /** Null for a thread between turns (idle, your turn) or without its claude here (not started, exited, elsewhere). */
    fun busy(state: ThreadState): Busy? = when (state) {
        ThreadState.Starting, ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, is ThreadState.NeedsYou -> Busy.WORKING
        ThreadState.Unknown -> Busy.MAYBE
        ThreadState.Idle, is ThreadState.YourTurn, ThreadState.NotStarted, ThreadState.Exited, is ThreadState.RunningElsewhere -> null
    }

    fun isBusy(state: ThreadState): Boolean = busy(state) != null

    /** The balloon for threads that turned busy between the question and the close and so stay open, by [names]. */
    fun leftOpenText(names: List<String>): String =
        "${names.joinToString(", ")} started working, so Skein left ${if (names.size == 1) "it" else "them"} open"
}
