package io.github.v0rest.skein.threads

/**
 * A thread's colour is the colour of its session too. The thread's record keeps the colour the row and the editor tab
 * show, and [SessionMarks.colors] remember it per session, so a thread that resumes the session from History after the
 * old one closed gets it back ([ThreadCoordinator.resume]). Pure - [ThreadService] calls it on its confined dispatcher.
 */
internal object SessionColors {
    /**
     * The colour the user gave [threadIds] goes to the session each of them holds, and null takes it away there. A
     * thread without a session yet keeps the colour on its record alone, until [fill] sees the session come.
     */
    fun set(marks: SessionMarks, threads: List<SkeinThread>, threadIds: Collection<String>, color: ThreadColor?): SessionMarks {
        val ids = threadIds.toSet()
        val sessions = threads.filter { it.id in ids }.mapNotNull { it.sessionId }
        if (sessions.isEmpty()) return marks
        return marks.color(sessions, color)
    }

    /**
     * A coloured thread whose session is not the one it had at the last look - [previous] maps thread ids to the session
     * each had then, and a thread missing there is new since - gives its colour to that session, unless the session
     * has a colour already. That is a thread coloured before its claude started, a thread whose session changed
     * (`/clear`, a resume under a new id) when the old session had no colour to carry ([SessionMarks.carry] runs first and
     * wins), and a thread restored with the colour the workspace file kept for it. The service starts each IDE run with
     * no last look, so a restored thread does this at every start, never over a session's own colour. A thread that kept
     * its session adds nothing, so within a run a colour History moved on to a later part of the conversation
     * ([SessionMarks.follow]) does not come back. Of two such threads on one session the first decides.
     */
    fun fill(marks: SessionMarks, previous: Map<String, String?>, threads: List<SkeinThread>): SessionMarks {
        var colors = marks.colors
        for (thread in threads) {
            val session = thread.sessionId ?: continue
            val color = thread.color ?: continue
            if (previous[thread.id] == session || session in colors) continue
            colors = colors + (session to color)
        }
        return if (colors == marks.colors) marks else marks.copy(colors = colors)
    }
}
