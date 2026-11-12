package io.github.v0rest.skein.terminal

/**
 * What Skein's close check answers when the IDE is about to close a tab on the user's word (the editor tab's ×, Close,
 * Close All, Close Others, a Terminal tool window tab's close; never a move, which does not ask, nor a project close or
 * the IDE quitting, which pass when they do ask). A terminal tab closed while its shell runs leaves the shell, and a claude in it, hanging forever,
 * because the IDE keeps the tab's pty open but stops reading it (IJPL-259859); so a Skein thread tab whose session runs
 * stays open until Skein has ended the thread there and closes the tab itself.
 */
object CloseGate {
    enum class Decision {
        /** Let it close now. */
        PASS,

        /** Keep it open: Skein is already ending the thread and closes the tab itself afterwards. */
        WAIT,

        /** Keep it open and end the thread; Skein then closes the tab itself. */
        END_FIRST,
    }

    /**
     * [skeinTab]: a thread tab of this project. [closing]: the project closes or the IDE quits. [released]: Skein closes
     * the tab itself now. [running]: the tab's terminal session runs (not one that ended, nor a restored tab whose shell
     * has not started yet). [views]: the editor windows showing the tab. [endingForMillis]: how long ago Skein started
     * ending the thread, or null. An ending older than [ENDING_GRACE_MILLIS] never got to its close, and is given up.
     */
    fun decide(skeinTab: Boolean, closing: Boolean, released: Boolean, running: Boolean, views: Int, endingForMillis: Long?): Decision = when {
        // closing one of two views of a tab leaves the terminal running in the other one
        !skeinTab || closing || released || !running || views > 1 -> Decision.PASS
        endingForMillis == null -> Decision.END_FIRST
        endingForMillis < ENDING_GRACE_MILLIS -> Decision.WAIT
        // an ending that never got to its close (something failed and its mark stayed) gives up: the tab closes as before
        else -> Decision.PASS
    }

    /** One thread tab of a close the IDE asks about, with what [decide] needs to know of it. */
    data class Tab(val id: String, val released: Boolean, val running: Boolean, val views: Int, val endingForMillis: Long?)

    /**
     * The answer for a whole close: [pass] lets every file of it close. Otherwise the IDE keeps them all open, Skein ends
     * the threads of the tabs [take] and closes those tabs itself, and [waiting] are tabs whose ending is already under
     * way, which close when it is done.
     */
    data class Batch(val pass: Boolean, val take: List<String>, val waiting: List<String>)

    fun decideBatch(tabs: List<Tab>, closing: Boolean): Batch {
        val decided = tabs.map { it to decide(skeinTab = true, closing, it.released, it.running, it.views, it.endingForMillis) }
        val waiting = decided.filter { it.second == Decision.WAIT }.map { it.first.id }
        if (waiting.isEmpty() && decided.none { it.second == Decision.END_FIRST }) return Batch(pass = true, take = emptyList(), waiting = emptyList())
        // the IDE keeps the whole close open now, so Skein closes every thread tab of it that would have gone: a running
        // one after its ending, one that does not run (a restored tab never opened) right away, in the same batch; not a
        // tab Skein closes already, and not one also open in another window, whose terminal stays in use there
        val take = decided.filter { (tab, decision) ->
            decision == Decision.END_FIRST || (decision == Decision.PASS && !tab.running && !tab.released && tab.views <= 1)
        }.map { it.first.id }
        return Batch(pass = false, take = take, waiting = waiting)
    }

    /**
     * The answer when the Terminal tool window is about to remove a thread tab's content (the tab's ×, Close Tab, the
     * tool window's close actions): a running thread is ended first there too, since the IDE leaves a closed tab's pty
     * unread there as well (IJPL-259859). [move] is a removal the terminal plugin marks with `Content.TEMPORARY_REMOVED_KEY`,
     * Move to Editor or a drag out of the tool window, where the terminal goes on in its new place: it always passes, as
     * does a project close or an IDE quit ([closing]). The tab is one view whatever [tab] says.
     */
    fun decideTerminalWindowClose(tab: Tab, closing: Boolean, move: Boolean): Batch =
        if (move) Batch(pass = true, take = emptyList(), waiting = emptyList()) else decideBatch(listOf(tab.copy(views = 1)), closing)

    /**
     * True when [can] says yes to every one of [items], asking about each one even after a no, unlike `all`: each
     * project's thread tabs in a close must be looked at, not only up to the first project that says no.
     */
    fun <T> askAll(items: Iterable<T>, can: (T) -> Boolean): Boolean {
        var all = true
        for (item in items) if (!can(item)) all = false
        return all
    }

    /** How long a second close waits for an ending already under way: the tab's setup wait, the end wait, and slack. */
    const val ENDING_GRACE_MILLIS = 15_000L
}
