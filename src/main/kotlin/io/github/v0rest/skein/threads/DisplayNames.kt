package io.github.v0rest.skein.threads

/** The name Skein shows for a thread or session: the user's own name for the session, else Claude Code's. */
object DisplayNames {
    fun thread(thread: SkeinThread, names: Map<String, String>): String =
        thread.sessionId?.let { names[it] } ?: thread.name

    fun session(sessionId: String, fallback: String, names: Map<String, String>): String = names[sessionId] ?: fallback

    /**
     * Names follow a thread to its new session (`/clear`): [previous] and [current] map thread ids to session ids.
     * A new session that already has a name keeps it; threads that are new or gone change nothing.
     */
    fun carry(previous: Map<String, String?>, current: Map<String, String?>, names: Map<String, String>): Map<String, String> {
        var result = names
        for ((threadId, before) in previous) {
            val after = current[threadId] ?: continue
            if (before == null || before == after) continue
            val name = names[before] ?: continue
            if (after in result) continue
            result = result + (after to name)
        }
        return result
    }
}
