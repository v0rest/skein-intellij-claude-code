package io.github.v0rest.skein.threads

/**
 * What the user marked on Claude sessions themselves (not on threads): History pins, Skein names and done marks,
 * all keyed by session id. A pure value: every method returns a new one.
 */
data class SessionMarks(
    val pinned: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    val done: Map<String, Long> = emptyMap(),
) {
    fun pin(ids: Collection<String>, on: Boolean): SessionMarks = copy(pinned = if (on) pinned + ids else pinned - ids.toSet())

    /** A blank or null name clears the Skein name. */
    fun rename(id: String, name: String?): SessionMarks {
        val clean = name?.trim().orEmpty()
        return copy(names = if (clean.isEmpty()) names - id else names + (id to clean))
    }

    fun markDone(ids: Collection<String>, at: Long): SessionMarks = copy(done = done + ids.associateWith { at })

    fun undone(ids: Collection<String>): SessionMarks = copy(done = done - ids.toSet())

    /** [into] now stands for the conversation of the earlier sessions [from]: it takes over each kind of mark it has none of, and the earlier ones lose theirs. */
    fun follow(into: String, from: Collection<String>): SessionMarks {
        val earlier = from.filter { it != into }
        val pin = into in pinned || earlier.any { it in pinned }
        val name = names[into] ?: earlier.firstNotNullOfOrNull { names[it] }
        val doneAt = done[into] ?: earlier.firstNotNullOfOrNull { done[it] }
        val gone = earlier.toSet()
        return SessionMarks(
            pinned = (pinned - gone).let { if (pin) it + into else it },
            names = (names - gone).let { if (name != null) it + (into to name) else it },
            done = (done - gone).let { if (doneAt != null) it + (into to doneAt) else it },
        )
    }

    /** Every session that has a mark of any kind. */
    fun ids(): Set<String> = pinned + names.keys + done.keys

    /**
     * Drops the marks of sessions in neither set: not found on disk any more and not held by a thread. Only the
     * sessions in [examined] are judged: a mark made after the scan sampled the store waits for the next scan.
     */
    fun prune(known: Set<String>, held: Set<String>, examined: Set<String>): SessionMarks {
        val keep = { id: String -> id !in examined || id in known || id in held }
        return SessionMarks(pinned.filter(keep).toSet(), names.filterKeys(keep), done.filterKeys(keep))
    }
}
