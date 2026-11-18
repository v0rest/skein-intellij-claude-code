package io.github.v0rest.skein.terminal

import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

/**
 * What becomes of a thread's editor tab that the IDE closes past Skein's close check. The IDE's restore of a branch's
 * workspace ("Restore workspace on branch switching", `BranchContextTracker` of the Tasks and Contexts plugin), its
 * balloon's Rollback and the plugin's Load Context all clear the editor area with `FileEditorManagerImpl.closeAllFiles`,
 * which asks no `VirtualFilePreCloseCheck`, and the terminal's editor then disposes the terminal - the IDE hangs up the
 * shell, and a claude busy in it lives on with nobody reading it, or ends in the middle of its answer (javap of 2026.3,
 * build 263.7307.200). IDE-free, so each decision is tested on its own, and [TerminalAdapter] only gathers the facts.
 *
 * Skein has two answers, the first wherever it knows ahead.
 * - Right before a branch switch made through the IDE, Skein puts the IDE's own "closes to reopen" mark ([ReopenMark])
 *   on its thread tabs whose terminal runs. The terminal then lives through the restore's clear, and Skein opens the
 *   same tab again right after ([Close.REOPEN_SAME]), so claude never notices.
 * - A thread tab the clear takes without that mark gets its claude ended cleanly first, while the tab is still open and
 *   read, the way a project close ends it - every such tab of the clear together, under one deadline ([endTogether]) -
 *   and the thread comes back right after as a Resume of its session ([Close.END_THEN_RESUME]). An answer claude was in
 *   the middle of is lost. On Windows the IDE ends claude with the tab and Skein sends no hang-up, so there the thread
 *   only comes back. A restored thread nobody opened gets no mark, since opening its tab again would start it, and comes
 *   back the same way, not started.
 *
 * A marked tab closed on its own, outside a clear, is either a drag of the IDE's own or the user's close that skips the
 * check (a middle click or a Shift+click on an editor tab, or a Shift+Alt+click on another tab of its window, which
 * `TabMouseListener` closes without the checks, a plugin's `closeFile`). The mark keeps its terminal alive through that close. A drag's drop opens the file again later, in a
 * coroutine of its own (`EditorTabbedContainerDragOutDelegate.dragOutFinished` clears the mark first, and
 * `DockableEditorTabbedContainer.add` launches the open), so Skein waits [SINGLE_CLOSE_WAIT_MILLIS] and the tab turning
 * up again cancels the wait ([SingleCloses]). A tab still in no place then was a real close, and Skein ends its claude
 * the way Close does and lets it go - unless the restore closed it (a floating editor window's tab, closed with the
 * branch change it was marked for already there or in that very turn of the EDT), and then it comes back as a Resume
 * ([afterSingleClose], [Close.END_IF_CLOSED]). Skein never opens such a tab again itself.
 *
 * An unmarked thread tab closed on its own past the check, outside a clear, is the user's close that skips it - a middle
 * click or a Shift+click on the tab, a Shift+Alt+click on another tab of its window, which closes every other tab there
 * (`TabMouseListener.mouseReleased`, `UIUtil.isCloseClick` is button 2 or Shift with button 1, and a plain Alt+click
 * closes nothing), the Switcher's close (the Recent Files plugin's `closeEditorForFile`), a floating
 * editor window closed with its own close button (`DockWindow` -> `DockableEditorTabbedContainer.closeAll`, file by file),
 * a plugin's `closeFile` - and the terminal goes with it, since only a marked file keeps it. A claude busy there would be
 * left blocked on a pty nobody reads, so Skein ends it first, the way a clear's tabs are ended but on its own, and the
 * close goes on as the user's - the thread goes as after Close ([Close.END_THEN_CLOSE]). On Windows, and for a tab whose
 * terminal does not run, it stays a plain close.
 *
 * The user's own close through the check (×, Close, Close All, the thread menu's Close, the Terminal tool window's close),
 * a move the IDE marks itself, Skein's own close and a project close stay exactly as they are ([Close.LET_GO]).
 */
object TabRestore {
    /**
     * A thread tab as Skein sees it when a branch is about to change. [inEditor] - an open editor file shows it.
     * [markedByTheIde] - that file carries the reopen mark and Skein did not put it there, a move under way.
     * [released] - Skein closes the tab itself now. [ending] - Skein ends the thread on the user's word and closes it then.
     * [started] - its terminal session has started, so it is no restored thread nobody opened yet. [closingPastCheck] -
     * the IDE is closing it past the check right now, its claude being ended while that close waits ([Close.END_THEN_RESUME],
     * [Close.END_THEN_CLOSE]), or a past-check close of it is not settled yet.
     */
    data class Tab(
        val id: String,
        val inEditor: Boolean,
        val markedByTheIde: Boolean,
        val disposed: Boolean,
        val released: Boolean,
        val ending: Boolean,
        val started: Boolean,
        val closingPastCheck: Boolean,
    )

    /**
     * The thread tabs whose editor file gets Skein's reopen mark before a branch switch - those open in the editor, with a
     * terminal that runs, and not on their way out. A thread in the Terminal tool window has no editor file, and the
     * restore leaves it alone. Nor does a tab the IDE is closing past the check get one - the wait for its claude pumps
     * events, a branch switch announced off the EDT can come in then, and a mark would keep its hung-up terminal through
     * the close, with no tab and the row left behind.
     */
    fun toMark(tabs: List<Tab>): List<String> =
        tabs.filter { it.inEditor && it.started && !it.markedByTheIde && !it.disposed && !it.released && !it.ending && !it.closingPastCheck }
            .map { it.id }

    enum class Close {
        /**
         * Not Skein's to change - the user's own close through the check, a move the IDE marked, Skein's own close, a project
         * close, and a single close where nothing runs or nothing can be hung up.
         */
        LET_GO,

        /** Skein's own mark in a clear - the terminal lives through the close, and Skein opens the same tab again right after. */
        REOPEN_SAME,

        /** Skein's own mark on a tab closed on its own - kept through the close, and the tab ended if it turns up nowhere ([afterSingleClose]). */
        END_IF_CLOSED,

        /** A clear past the close check without a mark - claude is ended now, and the thread comes back as a Resume. */
        END_THEN_RESUME,

        /**
         * One running thread tab closed past the close check without a mark - claude is ended now, while the tab is still
         * open and read, and the close goes on as the user's, so the thread goes as after Close.
         */
        END_THEN_CLOSE,
    }

    /**
     * How Skein meets the IDE closing a thread's editor file, decided right before the close disposes anything
     * (`FileEditorManagerListener.Before.beforeFileClosed`). [reopenMark] - the file carries the reopen mark now, whoever
     * put it there. [markedBySkein] - Skein put it there for a branch switch. [released] - Skein closes the tab itself.
     * [askedByUser] - Skein's close check was asked about this close and let it through, which only the user's closes
     * and Skein's own do. [ending] - Skein ends the thread already on the user's word. [closing] - the project closes or
     * the IDE quits. [wholeArea] - the close is part of a clear of the editor area ([wholeArea]). [running] - the tab's
     * terminal session runs, so it is no restored thread nobody opened and no shell that has ended. [closeCanHang] -
     * Skein's hang-up sends anything here ([CloseGate.closeCanHang]).
     */
    fun beforeClose(
        reopenMark: Boolean,
        markedBySkein: Boolean,
        released: Boolean,
        askedByUser: Boolean,
        ending: Boolean,
        closing: Boolean,
        wholeArea: Boolean,
        running: Boolean,
        closeCanHang: Boolean,
    ): Close = when {
        closing || released || askedByUser || ending -> Close.LET_GO
        reopenMark && markedBySkein -> if (wholeArea) Close.REOPEN_SAME else Close.END_IF_CLOSED
        // the IDE's own mark, a move to another split or window, or into the Terminal tool window
        reopenMark -> Close.LET_GO
        wholeArea -> Close.END_THEN_RESUME
        // one tab past the check - a middle click, a Shift+click, a Shift+Alt+click on another tab, the Switcher, a floating
        // editor window's close, a plugin's closeFile. The terminal goes with the tab, so a running claude is ended first, and the close stays the user's.
        // Where nothing runs there is nothing to hang up, and on Windows the IDE ends claude itself
        running && closeCanHang -> Close.END_THEN_CLOSE
        else -> Close.LET_GO
    }

    /**
     * Whether a close is part of a clear of the editor area. `closeAllFiles` takes the editor windows down before it
     * closes their files, so [windowsGone] - no window lists the file any more - says so for its files, and the restore
     * then closes the floating editor windows' files one by one in the same turn of the EDT, their window still there,
     * which [soFar] keeps in the clear. A single close leaves its file in its window until after the decision. A clear
     * whose main editor area has no file at all starts with a floating window's file, its window still there, so it is
     * taken for single closes - a running thread there is ended and goes as after Close, not back as a Resume. Accepted -
     * nothing tells it from a floating window closed with its own close button, and the thread is ended cleanly either way.
     */
    fun wholeArea(soFar: Boolean, windowsGone: Boolean): Boolean = soFar || windowsGone

    /**
     * The thread tabs the first past-check close of a clear ends together, in one call under one deadline, the way a
     * project close ends its threads, so a slow claude early in the clear costs the later ones nothing.
     */
    data class ClearTab(
        val id: String,
        /** The clear takes it - an editor tab no window lists any more, not one in another window or the Terminal tool window. */
        val inThisClear: Boolean,
        val released: Boolean = false,
        val ending: Boolean = false,
        val askedByUser: Boolean = false,
        /** Skein's own mark keeps its terminal through the clear. */
        val keptByMark: Boolean = false,
    )

    fun endTogether(tabs: List<ClearTab>): List<String> =
        tabs.filter { it.inThisClear && !it.released && !it.ending && !it.askedByUser && !it.keptByMark }.map { it.id }

    /** Whether Skein's mark stays on the file for this close. Any other answer takes it off first, so the terminal goes as decided. */
    fun keepsMark(close: Close): Boolean = close == Close.REOPEN_SAME || close == Close.END_IF_CLOSED

    /** What Skein ends right away, inside the IDE's close, for the answer [beforeClose] gave ([endNow]). */
    enum class EndNow {
        NOTHING,

        /** Every thread tab the clear takes, together ([endTogether], [PastCheckEnding.ofTheClear]). */
        WITH_THE_CLEAR,

        /** This one tab ([PastCheckEnding.ofASingleClose]). */
        THIS_TAB,
    }

    /**
     * What [close] has Skein end now. [endedThisTurn] - Skein ended this tab already in the same turn of the EDT, with the
     * clear's first close or a close of it a moment before, so nothing is ended twice.
     */
    fun endNow(close: Close, endedThisTurn: Boolean): EndNow = when {
        endedThisTurn -> EndNow.NOTHING
        close == Close.END_THEN_RESUME -> EndNow.WITH_THE_CLEAR
        close == Close.END_THEN_CLOSE -> EndNow.THIS_TAB
        else -> EndNow.NOTHING
    }

    /**
     * How long the IDE's close waits inside it for Skein to end the threads it closes past the close check - a clear's all
     * together, as a project's close does, a single close's one - claude and then the shell.
     */
    const val PAST_CHECK_END_MILLIS = 2_000L

    /**
     * What Skein hands its listener for tabs the IDE closes past the check
     * ([io.github.v0rest.skein.terminal.ThreadTerminalListener.tabsClosingPastCheck]) - which threads, how long the close
     * waits for them, and whether they come back ([comesBack]) or go as after Close.
     */
    data class PastCheckEnding(val ids: List<String>, val withinMillis: Long, val comesBack: Boolean) {
        companion object {
            /** A clear's tabs, ended together - with nothing running among them nothing is waited for, and they come back. */
            fun ofTheClear(ids: List<String>, anyRunning: Boolean) =
                PastCheckEnding(ids, withinMillis = if (anyRunning) PAST_CHECK_END_MILLIS else 0, comesBack = true)

            /** One running tab the user closed on its own, ended under the same short deadline. */
            fun ofASingleClose(id: String) = PastCheckEnding(listOf(id), withinMillis = PAST_CHECK_END_MILLIS, comesBack = false)
        }
    }

    /**
     * Whether a past-check close that had Skein end claude in its turn of the EDT is dropped once that turn is over and the
     * tab is still open - the close did not take it after all, so a later close of it is an ordinary one.
     */
    fun forgottenIfStillOpen(pastCheck: Close?): Boolean = pastCheck == Close.END_THEN_RESUME || pastCheck == Close.END_THEN_CLOSE

    /** What a marked tab closed on its own turns out to be once Skein has waited for it ([afterSingleClose]). */
    enum class SingleClose {
        /** It turned up elsewhere (a move), is gone, or Skein closes it itself - nothing to do. */
        NOTHING,

        /** A real close past the check - claude is ended the way Close ends it, and the thread goes. */
        CLOSE,

        /** The restore closed it - claude is ended cleanly, and the thread comes back as a Resume. */
        LOSE,
    }

    /**
     * A marked tab closed on its own ([Close.END_IF_CLOSED]), [SINGLE_CLOSE_WAIT_MILLIS] later and not seen again
     * meanwhile ([SingleCloses]). One open again in the editor or the Terminal tool window was moved, one gone has nothing
     * left to end, and one Skein closes itself ([released]) is let go. One in no place ([TabPlaces.Where.MOVING]) by then
     * was closed for good - by the restore when [lost] ([SingleCloses.take]), a floating editor window's tab closed one by
     * one with its window still there, and the thread comes back, else by the user or a plugin, and the thread goes.
     */
    fun afterSingleClose(where: TabPlaces.Where?, released: Boolean, lost: Boolean): SingleClose = when {
        where != TabPlaces.Where.MOVING || released -> SingleClose.NOTHING
        lost -> SingleClose.LOSE
        else -> SingleClose.CLOSE
    }

    /**
     * Whether the branch change a mark was put there for (at [markedAt]) had come by now - [changedAt] is the last one,
     * monotonic millis. One before the mark was another checkout's.
     */
    fun changedBefore(markedAt: Long, changedAt: Long?): Boolean = changedAt != null && changedAt >= markedAt

    /**
     * The marked tabs closed on their own that Skein waits for ([afterSingleClose]), by thread id. Whether the restore
     * closed a tab is settled by its close itself, never by what comes during the wait, or a checkout that ends a moment
     * after the user's middle click would turn that close into the restore's.
     *
     * A close that came from the user's own mouse or keys (`byUserInput`, [closedByUsersInput]) is the user's whatever
     * else holds - no restore runs on such an event, and the marks the restore did not use stay a second after a checkout
     * succeeded. Any other close was the
     * restore's when the branch change its mark was put there for had come before the close ([changedBefore]), or comes
     * in the very turn of the EDT the close came in (`turn`, the event dispatched then) - the restore runs inside the
     * dispatch of that change, and Skein's own listener of it may come after the restore.
     *
     * That turn is one event of the EDT, and the IDE runs a batch of queued `invokeLater` runnables in one event. So a
     * plugin's `closeFile` that runs in the same batch as the IDE's `branchHasChanged` is taken for the restore's close,
     * and its thread comes back as a Resume - accepted, as such a close in that very batch is as good as never seen.
     *
     * The tab turning up again ([seenAgain]) cancels its wait, whenever the drop comes. EDT only.
     */
    class SingleCloses {
        private class Waiting(val changedBefore: Boolean, val turn: Any?, val byUserInput: Boolean)

        private val waiting = HashMap<String, Waiting>()

        fun closed(id: String, changedBefore: Boolean, turn: Any?, byUserInput: Boolean) {
            waiting[id] = Waiting(changedBefore, turn, byUserInput)
        }

        /** The tab shows again, so its close was a move - true when a wait was cancelled. */
        fun seenAgain(id: String): Boolean = waiting.remove(id) != null

        /**
         * The wait is over. Null when the tab was seen again meanwhile, else whether the restore closed it - [lastChangeTurn]
         * is the turn of the EDT the last branch change came in.
         */
        fun take(id: String, lastChangeTurn: Any?): Boolean? {
            val close = waiting.remove(id) ?: return null
            if (close.byUserInput) return false
            return close.changedBefore || (close.turn != null && lastChangeTurn === close.turn)
        }
    }

    /**
     * Whether the event the EDT dispatches as a tab closes is the user's own input that close comes from - a mouse event
     * when [mouse], a key event when [key], with its [id]. The editor tab's middle click or Shift+click, and the
     * Shift+Alt+click that closes the window's other tabs, close in `TabMouseListener.mouseReleased`, on `MouseEvent.MOUSE_RELEASED` (`UIUtil.isCloseClick(e, 502)`, javap of
     * 2026.3, while `mousePressed` takes double clicks and `mouseClicked` the path popup), and a shortcut runs its action
     * on `KeyEvent.KEY_PRESSED`. No other input counts, since the dispatcher's current event is not restored after a nested
     * event loop - inside the restore it can be a stale mouse move or click over a progress dialog it showed.
     */
    fun closedByUsersInput(mouse: Boolean, key: Boolean, id: Int): Boolean =
        mouse && id == MouseEvent.MOUSE_RELEASED || key && id == KeyEvent.KEY_PRESSED

    /**
     * How long Skein waits for a marked tab closed on its own to turn up again before it takes the close for a real one. A
     * drop opens the file again within milliseconds as a rule, and a few seconds leave room for a busy IDE, while the
     * mark keeps the terminal alive, so the wait costs nothing but a user's close ending a few seconds later.
     */
    const val SINGLE_CLOSE_WAIT_MILLIS = 5_000L

    /** What the end of a thread tab's terminal means for the thread ([gone]). */
    enum class Gone {
        /** The tab closed for good, and the thread goes, as it always did. */
        CLOSED,

        /** Skein ended claude before the IDE closed the tab, and the thread comes back as a Resume. */
        BACK_AS_RESUME,

        /** The tab carried Skein's mark in a clear and its terminal ended anyway - nothing could be hung up first, and the thread comes back as a Resume. */
        TERMINAL_DIED,

        /** The project closes, and nothing is reported, as before. */
        NOTHING,
    }

    /** The tab's terminal was disposed. [pastCheck] is how Skein met the close that disposed it, or null for any other close. */
    fun gone(projectClosing: Boolean, pastCheck: Close?): Gone = when {
        projectClosing -> Gone.NOTHING
        pastCheck == Close.END_THEN_RESUME -> Gone.BACK_AS_RESUME
        pastCheck == Close.REOPEN_SAME -> Gone.TERMINAL_DIED
        // a single close Skein ended claude for, and a tab closed on its own whose terminal went anyway, were the user's
        // close
        else -> Gone.CLOSED
    }

    /**
     * After the IDE's clear closed a marked tab and its terminal lives - whether Skein opens the same tab again now. Only
     * while the tab is in neither place ([TabPlaces.Where.MOVING]), since something may have opened it again already, and
     * never a tab Skein closes itself ([released]).
     */
    fun reopensSame(where: TabPlaces.Where?, released: Boolean): Boolean = where == TabPlaces.Where.MOVING && !released

    /**
     * Whether a tab that comes back is selected - only the one that was the selected editor when the close began, and only
     * if its terminal had started. Every other one comes back beside the editor the restore selected, and a tab whose
     * session never started (a restored thread nobody opened) is never shown, as that would start its claude.
     */
    fun selects(wasInFront: Boolean, started: Boolean): Boolean = wasInFront && started

    /**
     * The thread ids whose mark comes off now, from [marked] (thread id to when Skein put the mark there, monotonic
     * millis). [changedAt] is when the branch change that has settled now was over, and every mark from before it that no
     * restore used comes off ([awaitingReopen] take theirs off when they open again), while one a newer branch switch put
     * there stays for that one. Without it only a mark [MARK_LIMIT_MILLIS] old comes off, since a checkout that fails
     * never says the branch changed.
     */
    fun toLift(marked: Map<String, Long>, awaitingReopen: Set<String>, changedAt: Long?, now: Long): List<String> =
        marked.filter { (id, since) ->
            id !in awaitingReopen && (changedAt != null && since <= changedAt || now - since >= MARK_LIMIT_MILLIS)
        }.keys.toList()

    /**
     * How long a mark waits for the branch change it was put there for. A checkout through the IDE reports a change only
     * when it succeeds, and a big repository or the Smart Checkout dialog can take minutes before it does, while a mark
     * that waits costs nothing more than a look at a tab closed on its own ([Close.END_IF_CLOSED]). So a checkout that
     * succeeds slowly still keeps its threads, and one that failed has its marks come off after this, so they do not
     * stay for good. A restore that comes even later takes the unmarked way - the thread ends and comes back as a Resume.
     */
    const val MARK_LIMIT_MILLIS = 10 * 60 * 1_000L
}
