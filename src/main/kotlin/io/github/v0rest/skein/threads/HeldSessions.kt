package io.github.v0rest.skein.threads

/**
 * The sessions a project's threads hold: a History scan never prunes their marks (pins, names, done), even when it
 * did not list their transcripts. The History's "running" label does not come from here but from the live status files.
 */
object HeldSessions {
    /** Until the saved threads are back ([restored] false) they hold their sessions too; after that only the live threads do. */
    fun of(restored: Boolean, live: List<SkeinThread>, saved: List<String?>): Set<String> {
        val ids = live.mapNotNullTo(HashSet()) { it.sessionId }
        if (!restored) saved.filterNotNullTo(ids)
        return ids
    }
}
