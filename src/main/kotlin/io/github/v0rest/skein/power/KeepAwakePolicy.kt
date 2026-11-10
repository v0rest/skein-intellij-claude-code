package io.github.v0rest.skein.power

import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState

/** Which threads keep the computer awake. A thread that waits for the user never does. */
object KeepAwakePolicy {
    private const val MILLIS_PER_MINUTE = 60_000L

    /** [backgroundMinutes]: 0 = background work does not hold, -1 = it holds as long as it runs, otherwise minutes. */
    fun holds(threads: List<SkeinThread>, now: Long, backgroundMinutes: Int): Boolean = threads.any {
        when (it.state) {
            ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Starting -> true
            ThreadState.Background ->
                backgroundMinutes == -1 || (backgroundMinutes != 0 && now - it.stateSince < backgroundMinutes * MILLIS_PER_MINUTE)
            is ThreadState.NeedsYou, is ThreadState.YourTurn, ThreadState.Idle, ThreadState.NotStarted,
            is ThreadState.RunningElsewhere, ThreadState.Exited, ThreadState.Unknown -> false
        }
    }
}
