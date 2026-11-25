package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.process.ProcessList

/** A terminal tab Skein opened for a thread. */
interface ThreadTab {
    val id: String
    val folder: String

    /**
     * The IDE's child processes right before Skein last sent a command into the tab ([ShellsAtCommand]). The tab's own
     * shell printed its prompt before that command went in, so it is among them; a terminal opened afterwards is not.
     */
    val shellsAtCommand: ShellsAtCommand
}

/** What the IDE's child processes were right before Skein last sent a command into a tab. */
sealed interface ShellsAtCommand {
    /** Skein has not sent a command into the tab yet. */
    data object NoCommandYet : ShellsAtCommand

    /** Skein sent one, but could not list the processes right before. */
    data object NotListed : ShellsAtCommand

    data class Listed(val processes: ProcessList) : ShellsAtCommand
}

/**
 * Which thread tabs the user has before them, read in one go ([ThreadTerminal.tabsInView]). [inView]: the ids of the
 * threads whose tab the user is looking at right now (the IDE frame is active and the tab is a selected editor, or
 * the selected tab of the visible Terminal tool window, settled in its place - [ViewRule]). [inFront]: the id of the
 * thread whose tab is the selected editor of this project, else one selected in the visible Terminal tool window,
 * whether or not the IDE or the project's window has focus, or null; unlike [inView] this is what the list marks, so
 * the mark stays while the user is in another application. [modal]: a modal dialog or a modal progress is open, so
 * whatever is in view sits behind it.
 */
data class TabsInView(val inView: Set<String>, val inFront: String?, val modal: Boolean = false)

/** What the coordinator needs from the IDE terminal; the only seam between the IDE-free core and the terminal plugin. */
interface ThreadTerminal {
    /**
     * Opens a terminal in [folder] running the user's shell, in the editor area or, as the setting says, in the Terminal
     * tool window; returns a handle at once, the tab appears on the EDT. The user may move the tab between the two
     * afterwards - it is the same thread there, closed only when its tab closes. [focus] leaves the new tab the selected
     * one, and without it the tab opens beside what was selected. [start] starts its shell at once, and without it the
     * shell starts only when the tab is first shown, as for the threads an IDE restart brings back.
     */
    fun open(threadId: String, folder: String, tabName: String, focus: Boolean, start: Boolean = focus): ThreadTab

    /** Types [command] plus Enter into the tab once the shell prompt is ready; returns at once. */
    fun type(tab: ThreadTab, command: String)

    /** Brings the tab forward where it is: its editor, or the Terminal tool window with the tab selected. */
    fun select(tab: ThreadTab)

    /**
     * Closes the tabs one after another on the EDT, the one in front last ([CloseOrder]), each the way its own close
     * button does, and returns at once. Whatever runs in a tab should have ended first ([awaitEnded]): the IDE stops
     * reading a closed terminal tab and leaves what still runs in it hanging. Skein's close check lets these tabs
     * through, and the IDE asks nothing for a Skein editor tab, so the caller asks first when it must; a tab left in
     * the Terminal tool window may also get the IDE's own question. A tab still being set up is waited for a few
     * seconds, then skipped. A tab that closes is reported like any other ([ThreadTerminalListener.tabClosed]).
     */
    fun close(tabs: List<ThreadTab>)

    /**
     * Skein starts ending what runs in these tabs and closes them afterwards ([close]): until then the IDE's close check
     * keeps them open and starts nothing new, so a × meanwhile does not end the thread a second time. Returns at once.
     */
    fun markEnding(tabs: List<ThreadTab>)

    /**
     * Skein gave up ending these threads before closing their tabs (it failed, or did not know the thread): the IDE's
     * close check stops holding their tabs, so the next close goes through. Returns at once.
     */
    fun clearEnding(threadIds: Collection<String>)

    /** Whether the tab's terminal session runs right now: false before its shell started and after it exited. */
    fun sessionRunning(tab: ThreadTab): Boolean

    /**
     * Whether the tab's terminal session has started, running or ended since. A tab that is not in view starts its shell
     * only when it is first shown. False when that cannot be told.
     */
    fun sessionStarted(tab: ThreadTab): Boolean

    /**
     * Whether the user may have typed into the tab since Skein set it up - a key, a paste, the terminal's Escape or an
     * input method's text, as the tab's key watch hears them ([DraftGuard.reachesInput]) - or nobody can tell, since the
     * tab is not watched. Skein then types nothing there on its own ([io.github.v0rest.skein.threads.InViewStart]), only
     * once the user opens the thread. Returns at once.
     */
    fun typedInto(tab: ThreadTab): Boolean

    /**
     * Suspends until the tab's terminal session has ended (its shell exited), and returns at once for a tab that could
     * not be set up; the caller bounds the wait.
     */
    suspend fun awaitEnded(tab: ThreadTab)

    /** The thread tabs the user looks at and the one in front ([TabsInView]), both read in one trip to the EDT. */
    suspend fun tabsInView(): TabsInView

    /** Asks the IDE to redraw the thread's editor tab (its colour); returns at once, does nothing without a tab. */
    fun refreshPresentation(threadId: String)

    /**
     * Names the thread's tab [title], over whatever title the program in the terminal sets; returns at once, does
     * nothing without a tab. A title that comes before the tab has appeared is applied once it does.
     */
    fun rename(threadId: String, title: String)

    /**
     * Whether claude's input in the tab is clean by the tab's key watch ([DraftGuard]): nothing typed since an Enter that
     * a turn right after it showed sent it, and no key for [DraftGuard.SETTLE_MILLIS]; a claude Skein just started counts
     * as dirty until then. A tab that cannot be watched is never clean, since then nobody can tell. Returns at once.
     */
    fun inputClean(tab: ThreadTab): Boolean

    /**
     * What Skein last saw each thread's claude do, for the key watch ([DraftGuard]): [enterMaySend] are the threads whose
     * claude is idle, where an Enter on a draft may send it, and [atWorkSince] says since when each thread at work (a
     * turn or a background task, not a dialog) has been so, in wall-clock millis - a turn that started right after such
     * an Enter shows it sent the draft ([DraftGuard.proven]). Returns at once.
     */
    fun claudeSeen(enterMaySend: Set<String>, atWorkSince: Map<String, Long>)

    /**
     * Types `/rename <name>` and Enter into the tab's claude ([ClaudeRename]), only if right then, in one turn of the EDT,
     * the tab's session runs and its input is still clean ([inputClean]), and [stillIdle], asked just before off the EDT,
     * says the thread's claude is still there and idle. [beforeSend] runs in that same EDT turn right before the line goes:
     * the caller takes the waiting rename there and says whether it still waits, so a rename the Rename dialog dropped or
     * replaced meanwhile is not typed, and an IDE that quits right after never types it again. True when the line went;
     * false and nothing typed otherwise.
     */
    suspend fun sendRename(tab: ThreadTab, name: String, stillIdle: () -> Boolean, beforeSend: () -> Boolean): Boolean

    fun setListener(listener: ThreadTerminalListener)
}

interface ThreadTerminalListener {
    fun tabClosed(tabId: String)

    fun tabSelected(tabId: String)

    /**
     * The user closed thread tabs in the IDE (an editor tab's ×, Close All, Close Others, or a Terminal tool window tab's
     * close) and one of them still runs its shell:
     * [tabIds] are all the thread tabs of that close, running or not. True when the listener takes the close over: it
     * ends the threads and then closes those tabs itself in one batch ([ThreadTerminal.close]), and the IDE keeps them
     * open meanwhile; false lets them close at once. Called on the EDT; returns at once and never throws.
     */
    fun closeRequested(tabIds: List<String>): Boolean

    /**
     * The IDE closes the thread tabs [tabIds] past Skein's close check without Skein's reopen mark, and their terminals go
     * right after this returns, so the listener ends what runs in them now, all together under one deadline of
     * [withinMillis], while the tabs are open and read. When [comesBack], the IDE is clearing the editor area - a restore
     * of a branch's workspace that came without the mark, its Rollback, the Tasks plugin's Load Context, another plugin
     * closing every editor ([TabRestore.Close.END_THEN_RESUME], [TabRestore.endTogether]) - and the listener decides how
     * each thread comes back ([tabLost]). Otherwise it is one running tab the user closed on its own past the check, a
     * middle click or a Shift+click ([TabRestore.Close.END_THEN_CLOSE]), and the thread goes once its tab is gone, as
     * after Close ([tabClosed]). [withinMillis] is 0 when no terminal among them runs, and then nothing is waited for.
     * Called on the EDT inside the IDE's close, blocks no longer than that, and never throws.
     */
    fun tabsClosingPastCheck(tabIds: List<String>, withinMillis: Long, comesBack: Boolean)

    /**
     * The IDE's restore closed the thread tabs [tabIds] one by one past Skein's close check while they carried its reopen
     * mark ([TabRestore.SingleClose.LOSE]), and their terminals live in no tab now. The listener ends what runs in them,
     * then has them released ([ThreadTerminal.close]), and each thread comes back as a Resume ([tabLost]). True when it
     * takes that over, false leaves the tabs to the caller. Called on the EDT, returns at once and never throws.
     */
    fun tabsLostPastCheck(tabIds: List<String>): Boolean

    /**
     * Thread [tabId]'s tab is gone past Skein's close check ([TabRestore.Gone.BACK_AS_RESUME],
     * [TabRestore.Gone.TERMINAL_DIED]), and the thread stays - the listener opens it again in a new tab, never asking
     * about its worktree. [select] - its tab was the selected editor when the close began. Returns at once, never throws.
     */
    fun tabLost(tabId: String, select: Boolean)
}
