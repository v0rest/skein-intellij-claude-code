package io.github.v0rest.skein.ui

import io.github.v0rest.skein.threads.DisplayNames
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState

/** How many threads need the user, their display names (longest waiting first), and the one that waited longest. */
data class WaitingCount(val count: Int, val names: List<String>, val longestWaitingId: String?)

object WaitingCounter {
    /** Only `NeedsYou` threads, ordered by how long they have waited (not by list position: pinned threads come first there). */
    fun of(threads: List<SkeinThread>, names: Map<String, String>): WaitingCount {
        val waiting = needsYou(threads)
        return WaitingCount(waiting.size, waiting.map { DisplayNames.thread(it, names) }, waiting.firstOrNull()?.id)
    }

    /** The thread "jump to waiting" opens: the longest-waiting needs-you one, else the oldest your-turn one. */
    fun jumpTarget(threads: List<SkeinThread>): String? =
        needsYou(threads).firstOrNull()?.id
            ?: threads.filter { it.state is ThreadState.YourTurn }
                .minWithOrNull(compareBy<SkeinThread> { (it.state as ThreadState.YourTurn).since }.thenBy { it.createdAt })?.id

    private fun needsYou(threads: List<SkeinThread>): List<SkeinThread> =
        threads
            .filter { it.state is ThreadState.NeedsYou }
            .sortedWith(compareBy<SkeinThread> { (it.state as ThreadState.NeedsYou).since }.thenBy { it.createdAt })
}
