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
 * the selected tab of the visible Terminal tool window). [inFront]: the id of the thread whose tab is the selected
 * editor of this project, else one selected in the visible Terminal tool window, whether or not the IDE or the
 * project's window has focus, or null; unlike [inView] this is what the list marks, so the mark stays while the user
 * is in another application.
 */
data class TabsInView(val inView: Set<String>, val inFront: String?)

/** What the coordinator needs from the IDE terminal; the only seam between the IDE-free core and the terminal plugin. */
interface ThreadTerminal {
    /**
     * Opens a terminal in [folder] running the user's shell, in the editor area or, as the setting says, in the Terminal
     * tool window; returns a handle at once, the tab appears on the EDT. The user may move the tab between the two
     * afterwards: it is the same thread there, closed only when its tab closes.
     */
    fun open(threadId: String, folder: String, tabName: String, focus: Boolean): ThreadTab

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
}
