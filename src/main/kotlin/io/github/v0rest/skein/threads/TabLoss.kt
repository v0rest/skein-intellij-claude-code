package io.github.v0rest.skein.threads

/**
 * How a thread comes back after the IDE closed its tab past Skein's close check
 * ([io.github.v0rest.skein.terminal.TabRestore], [ThreadCoordinator.onTabLost]) - in a new tab, as a Resume of its
 * session right away when claude ran in the old one, so the conversation goes on, and otherwise the way an IDE restart
 * brings a thread back, its claude resumed only once the tab is opened or stays in view a moment ([InViewStart]).
 */
object TabLoss {
    /**
     * True for a thread whose claude was in the tab - starting, at work, waiting for the user, between turns, or there
     * with a status Skein cannot read. False for one that never started there (a restored thread nobody opened), one
     * whose session runs in another terminal, and one whose claude had exited, which an IDE restart would not start
     * either.
     */
    fun resumesNow(state: ThreadState): Boolean = when (state) {
        ThreadState.NotStarted, is ThreadState.RunningElsewhere, ThreadState.Exited -> false
        ThreadState.Starting, ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Idle,
        is ThreadState.YourTurn, is ThreadState.NeedsYou, ThreadState.Unknown -> true
    }
}
