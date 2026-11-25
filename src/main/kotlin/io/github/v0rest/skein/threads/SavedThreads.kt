package io.github.v0rest.skein.threads

/**
 * The threads the workspace file keeps while its saved ones have not come back yet - in a project opened without trust
 * they wait until the user trusts it ([ThreadService.restoreOnce]) - and which of them come back then. One rule for all:
 * a saved thread whose id a live thread has, or whose session a live thread holds already, is that live thread now, and
 * a live thread on a waiting thread's session takes on what the user gave that one ([merged]).
 *
 * Nothing remembers that a live thread on a waiting thread's session closed: its session belongs to the waiting thread
 * again, which the next save writes and the trust brings back.
 */
object SavedThreads {
    /** Whether a live thread holds [sessionId] already, one the user resumed from the History meanwhile; never for no session. */
    fun heldByLive(sessionId: String?, liveSessions: Set<String>): Boolean = sessionId != null && sessionId in liveSessions

    /** The saved threads that still wait, in their own order: none once they are back ([restored]), else those no live thread is now. */
    fun waiting(restored: Boolean, saved: List<ThreadService.SavedThread>, live: List<SkeinThread>): List<ThreadService.SavedThread> {
        if (restored) return emptyList()
        val liveIds = live.mapTo(HashSet()) { it.id }
        val liveSessions = live.mapNotNullTo(HashSet()) { it.sessionId }
        return saved.filter { it.id !in liveIds && !heldByLive(it.sessionId, liveSessions) }
    }

    /**
     * [live], a thread on the session of the waiting thread [saved], with what the user gave that one and it has none of:
     * the pin, the colour, and the base its changes count against when both run in the same worktree. The name is not
     * taken - the user's own name for a session lives with the session ([DisplayNames.thread]), and the thread's name is
     * Claude Code's, which its claude reports again.
     */
    fun merged(live: SkeinThread, saved: SkeinThread): SkeinThread =
        live.copy(
            pinned = live.pinned || saved.pinned,
            color = live.color ?: saved.color,
            base = live.base ?: saved.base.takeIf { live.worktree != null && live.worktree == saved.worktree },
        )

    /**
     * What a save writes. Once the saved threads are back ([restored]) only the live ones. Until then the saved ones that
     * still wait ([waiting]), in their own order, and after them every live thread the user started meanwhile ("+", New
     * Thread Here, a resume from the History - Safe Mode allows those), so none of either is lost and the restore opens the
     * old ones first. Each thread once, and a live thread on a waiting thread's session is written [merged] with it.
     */
    fun toSave(restored: Boolean, saved: List<ThreadService.SavedThread>, live: List<SkeinThread>): List<ThreadService.SavedThread> {
        if (restored) return live.map(ThreadService::saved)
        val bySession = onSession(saved, live)
        return waiting(restored, saved, live) + live.map { thread -> ThreadService.saved(thread.sessionId?.let(bySession::get)?.let { merged(thread, it) } ?: thread) }
    }

    /**
     * The live threads and, until the saved threads are back ([restored]), the waiting ones after them as threads not
     * started yet - for the checks that must not take a waiting thread's worktree for a free one. A saved entry without
     * an id or a folder never comes back, so it counts for nothing.
     */
    fun withWaiting(restored: Boolean, saved: List<ThreadService.SavedThread>, live: List<SkeinThread>): List<SkeinThread> =
        live + waiting(restored, saved, live).mapNotNull(ThreadService::restored)

    /** The saved threads by session, the first in the file for each, leaving out those a live thread is by its id. */
    private fun onSession(saved: List<ThreadService.SavedThread>, live: List<SkeinThread>): Map<String, SkeinThread> {
        val liveIds = live.mapTo(HashSet()) { it.id }
        val found = HashMap<String, SkeinThread>()
        for (entry in saved) {
            if (entry.id in liveIds) continue
            val thread = ThreadService.restored(entry) ?: continue
            thread.sessionId?.let { found.putIfAbsent(it, thread) }
        }
        return found
    }
}
