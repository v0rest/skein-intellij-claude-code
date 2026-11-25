package io.github.v0rest.skein.threads

/**
 * When a thread that waits to be opened ([ThreadState.NotStarted] - brought back by a restart or a project opened again,
 * or after a lost tab) counts as opened with no click - its tab has been in view
 * ([io.github.v0rest.skein.terminal.TabsInView.inView]) for [DWELL_MILLIS] without a break, nobody typed into it, and
 * the shell in its terminal runs. Skein's own selections never count as the user's
 * ([io.github.v0rest.skein.terminal.OwnSelections]), and a click into a terminal that already holds the focus tells
 * nothing, so without this the tab a restore leaves in front, or the neighbour the IDE shows after Skein closes a tab,
 * sits at a bare shell whatever the user does there. A tab that only flashes past - in the middle of a restore or of a
 * batch of closes - does not start, and neither does one the user typed into, which goes on once they open it. The
 * moment runs on the clock the coordinator is given, so a burst of snapshots cannot make it shorter.
 */
object InViewStart {
    const val DWELL_MILLIS = 1_500L

    /**
     * Since when the tab has been in view, as of [now] - kept while it stays in view, null once it is out of view. A clock
     * that went back starts it again from [now], so the moment cannot run for ever.
     */
    fun inViewSince(since: Long?, inView: Boolean, now: Long): Long? = if (!inView) null else since?.takeIf { it <= now } ?: now

    /**
     * Whether the thread starts as if the user had opened it. The terminal is asked last, once the rest holds. A tab the
     * user typed into ([typedInto]) waits to be opened, since a line of theirs may sit at the prompt or a program of theirs
     * run there, and the command would join it. [shellRunning] comes after - a shell not started yet would keep the typed
     * command waiting until the tab is shown, and one that exited never takes it, while the launch slot of every window
     * waits for it.
     */
    fun starts(state: ThreadState, inViewSince: Long?, now: Long, typedInto: () -> Boolean, shellRunning: () -> Boolean): Boolean =
        state == ThreadState.NotStarted && inViewSince != null && now - inViewSince >= DWELL_MILLIS && !typedInto() && shellRunning()
}
