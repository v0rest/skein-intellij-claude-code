package io.github.v0rest.skein.threads

/**
 * What the user marked on Claude sessions themselves (not on threads): History pins, Skein names, done marks and
 * colours, all keyed by session id. A pure value: every method returns a new one.
 *
 * [claudeRenames] are Skein names that should also reach Claude Code and have not been typed into the session's claude
 * yet ([RenameGate]); each one is also the session's Skein name, and goes when that name changes or goes.
 *
 * [colors] remember the colour a thread of the session had ([SessionColors]), so a thread that resumes the session from
 * History gets it back after the old thread closed. The thread's own record keeps the colour it shows.
 */
data class SessionMarks(
    val pinned: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    val done: Map<String, Long> = emptyMap(),
    val claudeRenames: Map<String, String> = emptyMap(),
    val colors: Map<String, ThreadColor> = emptyMap(),
) {
    /** Gives the sessions [ids] the colour [color], and null takes theirs away. */
    fun color(ids: Collection<String>, color: ThreadColor?): SessionMarks =
        copy(colors = if (color == null) colors - ids.toSet() else colors + ids.associateWith { color })

    fun pin(ids: Collection<String>, on: Boolean): SessionMarks = copy(pinned = if (on) pinned + ids else pinned - ids.toSet())

    /**
     * A blank or null name clears the Skein name. With [inClaude] the name also waits to be typed into the session's
     * claude; without it a name that waited goes, since the newest rename decides.
     */
    fun rename(id: String, name: String?, inClaude: Boolean = false): SessionMarks {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return copy(names = names - id, claudeRenames = claudeRenames - id)
        return copy(names = names + (id to clean), claudeRenames = if (inClaude) claudeRenames + (id to clean) else claudeRenames - id)
    }

    /** Whether [name] is still the rename waiting for the session's claude: not dropped, not replaced by a newer one. */
    fun waitsFor(id: String, name: String): Boolean = claudeRenames[id] == name

    /** [name] was typed into the session's claude: it stops waiting, unless a newer rename came meanwhile. */
    fun claudeRenameSent(id: String, name: String): SessionMarks =
        if (waitsFor(id, name)) copy(claudeRenames = claudeRenames - id) else this

    /**
     * Claude Code shows [name] as the session's own name now: the Skein name goes, so a later `/rename` in claude is
     * followed again. Not when the user renamed the session since, or another rename waits.
     */
    fun claudeNameConfirmed(id: String, name: String): SessionMarks =
        if (names[id] == name && id !in claudeRenames) copy(names = names - id) else this

    fun markDone(ids: Collection<String>, at: Long): SessionMarks = copy(done = done + ids.associateWith { at })

    fun undone(ids: Collection<String>): SessionMarks = copy(done = done - ids.toSet())

    /**
     * [into] now stands for the conversation of the earlier sessions [from]: it takes over each kind of mark it has none of,
     * and the earlier ones lose theirs. A waiting rename goes along only with the name it is.
     */
    fun follow(into: String, from: Collection<String>): SessionMarks {
        val earlier = from.filter { it != into }
        val pin = into in pinned || earlier.any { it in pinned }
        val name = names[into] ?: earlier.firstNotNullOfOrNull { names[it] }
        val doneAt = done[into] ?: earlier.firstNotNullOfOrNull { done[it] }
        val waiting = claudeRenames[into] ?: earlier.firstNotNullOfOrNull { claudeRenames[it] }?.takeIf { it == name }
        val colour = colors[into] ?: earlier.firstNotNullOfOrNull { colors[it] }
        val gone = earlier.toSet()
        return SessionMarks(
            pinned = (pinned - gone).let { if (pin) it + into else it },
            names = (names - gone).let { if (name != null) it + (into to name) else it },
            done = (done - gone).let { if (doneAt != null) it + (into to doneAt) else it },
            claudeRenames = (claudeRenames - gone).let { if (waiting != null) it + (into to waiting) else it },
            colors = (colors - gone).let { if (colour != null) it + (into to colour) else it },
        )
    }

    /**
     * Names and colours follow a thread to its new session (`/clear`, a resume under a new id; [DisplayNames.carry]), and
     * a rename waiting for Claude Code goes along with its name, so it reaches the session the thread runs now.
     */
    fun carry(previous: Map<String, String?>, current: Map<String, String?>): SessionMarks {
        val carriedNames = DisplayNames.carry(previous, current, names)
        val carriedRenames = DisplayNames.carry(previous, current, claudeRenames).filter { (id, name) -> id in claudeRenames || carriedNames[id] == name }
        val carriedColors = DisplayNames.carry(previous, current, colors)
        if (carriedNames == names && carriedRenames == claudeRenames && carriedColors == colors) return this
        return copy(names = carriedNames, claudeRenames = carriedRenames, colors = carriedColors)
    }

    /** Every session that has a mark of any kind. */
    fun ids(): Set<String> = pinned + names.keys + done.keys + claudeRenames.keys + colors.keys

    /**
     * Drops the marks of sessions in neither set: not found on disk any more and not held by a thread. Only the
     * sessions in [examined] are judged: a mark made after the scan sampled the store waits for the next scan.
     */
    fun prune(known: Set<String>, held: Set<String>, examined: Set<String>): SessionMarks {
        val keep = { id: String -> id !in examined || id in known || id in held }
        return SessionMarks(
            pinned = pinned.filter(keep).toSet(),
            names = names.filterKeys(keep),
            done = done.filterKeys(keep),
            claudeRenames = claudeRenames.filterKeys(keep),
            colors = colors.filterKeys(keep),
        )
    }
}
