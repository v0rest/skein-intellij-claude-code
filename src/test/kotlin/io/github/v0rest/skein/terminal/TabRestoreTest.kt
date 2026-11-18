package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.terminal.TabRestore.Close
import io.github.v0rest.skein.terminal.TabRestore.Gone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabRestoreTest {
    private fun tab(
        id: String,
        inEditor: Boolean = true,
        markedByTheIde: Boolean = false,
        disposed: Boolean = false,
        released: Boolean = false,
        ending: Boolean = false,
        started: Boolean = true,
        closingPastCheck: Boolean = false,
    ) = TabRestore.Tab(id, inEditor, markedByTheIde, disposed, released, ending, started, closingPastCheck)

    @Test
    fun aBranchSwitchMarksTheThreadTabsOpenInTheEditor() {
        assertEquals(listOf("t1", "t3"), TabRestore.toMark(listOf(tab("t1"), tab("t2", inEditor = false), tab("t3"))))
    }

    /**
     * A tab the IDE is closing past the check while Skein ends its claude - the wait pumps events, and a branch switch
     * announced off the EDT reaches it then. A mark put there now would keep the hung-up terminal through the close, with
     * no tab and the row left behind, so such a tab gets none, in a clear or on its own.
     */
    @Test
    fun aTabWhosePastCheckCloseIsUnderWayIsNotMarked() {
        assertEquals(listOf("t2"), TabRestore.toMark(listOf(tab("t1", closingPastCheck = true), tab("t2"))))
    }

    /** A tab Skein closes itself, or ends on the user's word, is on its way out, and a restore must not bring it back. */
    @Test
    fun aTabOnItsWayOutIsNotMarked() {
        assertEquals(emptyList<String>(), TabRestore.toMark(listOf(tab("t1", released = true), tab("t2", ending = true), tab("t3", disposed = true))))
    }

    /**
     * A restored thread nobody opened has no terminal session yet, and opening its tab again would start it. It gets no
     * mark, the restore closes it, and it comes back unstarted ([TabLossTest]).
     */
    @Test
    fun aNeverStartedTabIsNotMarked() {
        assertEquals(listOf("t2"), TabRestore.toMark(listOf(tab("t1", started = false), tab("t2"))))
    }

    /** ...and the restore's close of it brings it back the way a lost tab comes back, never ended, never started. */
    @Test
    fun aNeverStartedTabInAClearComesBackAsALostTab() {
        assertEquals(Close.END_THEN_RESUME, close(running = false))
        assertEquals(Gone.BACK_AS_RESUME, TabRestore.gone(projectClosing = false, pastCheck = Close.END_THEN_RESUME))
    }

    /** The IDE's own mark is a move in progress, which takes the mark off when it is done, so Skein leaves that file alone. */
    @Test
    fun aFileTheIdeMarkedForAMoveIsLeftAlone() {
        assertEquals(listOf("t2"), TabRestore.toMark(listOf(tab("t1", markedByTheIde = true), tab("t2"))))
    }

    /**
     * By default a close of the whole editor area, as a restore, Rollback and Load Context make it, of a tab whose
     * terminal runs, on macOS or Linux.
     */
    private fun close(
        reopenMark: Boolean = false,
        markedBySkein: Boolean = false,
        released: Boolean = false,
        askedByUser: Boolean = false,
        ending: Boolean = false,
        closing: Boolean = false,
        wholeArea: Boolean = true,
        running: Boolean = true,
        closeCanHang: Boolean = true,
    ) = TabRestore.beforeClose(reopenMark, markedBySkein, released, askedByUser, ending, closing, wholeArea, running, closeCanHang)

    @Test
    fun aRestoreClosingAFileSkeinMarkedReopensTheSameTab() {
        assertEquals(Close.REOPEN_SAME, close(reopenMark = true, markedBySkein = true))
    }

    /** Rollback, Load Context, another plugin's close-all - the whole editor area past the close check, without a mark. */
    @Test
    fun aCloseWithoutAMarkPastTheCheckEndsClaudeThenResumes() {
        assertEquals(Close.END_THEN_RESUME, close())
    }

    /** Skein marked it, but a move since took every mark off - the terminal would go, so Skein ends claude first. */
    @Test
    fun aMarkTheIdeTookOffSinceCountsAsNoMark() {
        assertEquals(Close.END_THEN_RESUME, close(reopenMark = false, markedBySkein = true))
    }

    /** A drag to another split or window, into the Terminal tool window - the IDE's own mark, the terminal lives on. */
    @Test
    fun aMoveIsNeverARestoreNorAClose() {
        assertEquals(Close.LET_GO, close(reopenMark = true, markedBySkein = false, wholeArea = false))
        assertEquals(Close.LET_GO, close(reopenMark = true, markedBySkein = false))
    }

    /**
     * A middle click or a Shift+click on an editor tab closes it without the close check, and so do a Shift+Alt+click on
     * another tab of its window, the Switcher, a floating
     * editor window's own close and a plugin closing one file - one tab, its window still there. The terminal goes with
     * the tab, so a claude busy in it would be left blocked on a pty nobody reads. Skein ends claude first, while the tab
     * is still open and read, and the close goes on as the user's - the thread goes, never brought back.
     */
    @Test
    fun aRunningTabClosedOnItsOwnPastTheCheckEndsClaudeThenCloses() {
        assertEquals(Close.END_THEN_CLOSE, close(wholeArea = false))
    }

    /** Skein marked it for a branch switch, but a move since took the mark off - the terminal goes with this close. */
    @Test
    fun aMarkTheIdeTookOffSinceEndsASingleCloseToo() {
        assertEquals(Close.END_THEN_CLOSE, close(reopenMark = false, markedBySkein = true, wholeArea = false))
    }

    /** A restored thread nobody opened, or a shell that has ended - nothing runs there to hang up, a plain close. */
    @Test
    fun aTabClosedOnItsOwnWhoseTerminalDoesNotRunIsAPlainClose() {
        assertEquals(Close.LET_GO, close(wholeArea = false, running = false))
    }

    /** On Windows the IDE ends claude with the tab and Skein's hang-up sends nothing, so the close stays a plain one. */
    @Test
    fun onWindowsATabClosedOnItsOwnIsAPlainClose() {
        assertEquals(Close.LET_GO, close(wholeArea = false, closeCanHang = false))
    }

    /**
     * Around the single close - a move the IDE marks, Skein's own close, an ending under way, a close the check let
     * through, a project close, and Skein's own mark all keep their way, a running terminal or not.
     */
    @Test
    fun aSingleCloseOnlyEndsWhatNothingElseTakesCareOf() {
        assertEquals(Close.LET_GO, close(reopenMark = true, markedBySkein = false, wholeArea = false))
        assertEquals(Close.LET_GO, close(released = true, wholeArea = false))
        assertEquals(Close.LET_GO, close(ending = true, wholeArea = false))
        assertEquals(Close.LET_GO, close(askedByUser = true, wholeArea = false))
        assertEquals(Close.LET_GO, close(closing = true, wholeArea = false))
        assertEquals(Close.END_IF_CLOSED, close(reopenMark = true, markedBySkein = true, wholeArea = false))
    }

    /** A clear stays a clear - its threads come back as a Resume, whether their terminal runs, and on Windows too. */
    @Test
    fun aClearIsNeverTakenForASingleClose() {
        assertEquals(Close.END_THEN_RESUME, close(running = true))
        assertEquals(Close.END_THEN_RESUME, close(running = false))
        assertEquals(Close.END_THEN_RESUME, close(closeCanHang = false))
    }

    /** Once ended before the close, the tab's terminal going is the thread's close, as after Close, with its worktree question. */
    @Test
    fun aSingleCloseSkeinEndedFirstIsAClosedThread() {
        assertEquals(Gone.CLOSED, TabRestore.gone(projectClosing = false, pastCheck = Close.END_THEN_CLOSE))
        assertFalse(TabRestore.keepsMark(Close.END_THEN_CLOSE))
    }

    /**
     * The single close ends its own tab right away, and a clear's first close ends all of the clear's tabs - each only once
     * in a turn of the EDT. Nothing else ends anything inside the IDE's close.
     */
    @Test
    fun aSingleCloseEndsThisTabAndAClearItsTabsTogether() {
        assertEquals(TabRestore.EndNow.THIS_TAB, TabRestore.endNow(Close.END_THEN_CLOSE, endedThisTurn = false))
        assertEquals(TabRestore.EndNow.WITH_THE_CLEAR, TabRestore.endNow(Close.END_THEN_RESUME, endedThisTurn = false))
        for (close in Close.entries) assertEquals(close.name, TabRestore.EndNow.NOTHING, TabRestore.endNow(close, endedThisTurn = true))
        for (close in listOf(Close.LET_GO, Close.REOPEN_SAME, Close.END_IF_CLOSED)) {
            assertEquals(close.name, TabRestore.EndNow.NOTHING, TabRestore.endNow(close, endedThisTurn = false))
        }
    }

    /** The single close is ended under the clear's short deadline, and its thread goes as after Close, never back. */
    @Test
    fun aSingleCloseIsEndedUnderTheShortDeadlineAndGoes() {
        assertEquals(TabRestore.PastCheckEnding(listOf("t1"), 2_000, comesBack = false), TabRestore.PastCheckEnding.ofASingleClose("t1"))
        assertEquals(2_000L, TabRestore.PAST_CHECK_END_MILLIS)
    }

    /** A clear's threads come back, and with nothing running among them nothing is waited for. */
    @Test
    fun aClearsTabsAreEndedTogetherAndComeBack() {
        assertEquals(TabRestore.PastCheckEnding(listOf("t1", "t2"), 2_000, comesBack = true), TabRestore.PastCheckEnding.ofTheClear(listOf("t1", "t2"), anyRunning = true))
        assertEquals(TabRestore.PastCheckEnding(listOf("r1"), 0, comesBack = true), TabRestore.PastCheckEnding.ofTheClear(listOf("r1"), anyRunning = false))
    }

    /**
     * A tab Skein ended claude for in a close's turn of the EDT but that close did not take after all - a clear of another
     * editor window only, a single close that did not happen - keeps no past-check close once the turn is over.
     */
    @Test
    fun anEndedCloseThatDidNotHappenIsForgottenAfterItsTurn() {
        assertTrue(TabRestore.forgottenIfStillOpen(Close.END_THEN_RESUME))
        assertTrue(TabRestore.forgottenIfStillOpen(Close.END_THEN_CLOSE))
        // a marked tab waits for its reopen or its look, whatever the turn
        for (pastCheck in listOf(null, Close.LET_GO, Close.REOPEN_SAME, Close.END_IF_CLOSED)) {
            assertFalse(pastCheck.toString(), TabRestore.forgottenIfStillOpen(pastCheck))
        }
    }

    /**
     * A marked tab closed on its own, outside a clear of the editor area - a drag the IDE makes, or the user's middle click
     * that skips the close check. Both look the same then, so the mark stays and the terminal lives until a few seconds
     * later tell them apart ([TabRestore.afterSingleClose]), and it is never opened again by Skein.
     */
    @Test
    fun aMarkedTabClosedOutsideAClearIsLookedAtNotReopened() {
        assertEquals(Close.END_IF_CLOSED, close(reopenMark = true, markedBySkein = true, wholeArea = false))
        assertTrue(TabRestore.keepsMark(Close.END_IF_CLOSED))
    }

    private fun single(where: TabPlaces.Where?, released: Boolean = false, lost: Boolean = false) =
        TabRestore.afterSingleClose(where, released, lost)

    /** Still in no place a few seconds later, and not the restore's - a real close, ended as Close ends it. */
    @Test
    fun aMarkedTabStillInNoPlaceAfterItsCloseIsEnded() {
        assertEquals(TabRestore.SingleClose.CLOSE, single(TabPlaces.Where.MOVING))
    }

    /** The restore's own close of a floating editor window's tab - ended cleanly, and the thread comes back as a Resume. */
    @Test
    fun aMarkedTabTheRestoreClosedOnItsOwnIsLostNotClosed() {
        assertEquals(TabRestore.SingleClose.LOSE, single(TabPlaces.Where.MOVING, lost = true))
    }

    /** Open again in another split or window, or in the Terminal tool window - a drag, and nothing is ended. */
    @Test
    fun aMarkedTabThatTurnedUpElsewhereWasMoved() {
        assertEquals(TabRestore.SingleClose.NOTHING, single(TabPlaces.Where.EDITOR))
        assertEquals(TabRestore.SingleClose.NOTHING, single(TabPlaces.Where.TERMINAL_WINDOW, lost = true))
        // gone already, or Skein closes it itself
        assertEquals(TabRestore.SingleClose.NOTHING, single(TabPlaces.Where.GONE))
        assertEquals(TabRestore.SingleClose.NOTHING, single(null))
        assertEquals(TabRestore.SingleClose.NOTHING, single(TabPlaces.Where.MOVING, released = true))
    }

    /**
     * The branch change the mark was put there for had come before the close - the restore closed the tab right after it,
     * or later when a dialog held it back - so the tab is the restore's.
     */
    @Test
    fun aCloseAfterTheBranchChangeIsTheRestores() {
        assertTrue(TabRestore.changedBefore(markedAt = 1_000, changedAt = 1_000))
        assertTrue(TabRestore.changedBefore(markedAt = 1_000, changedAt = 4_000))
        // none yet, or one before this mark was another checkout's
        assertFalse(TabRestore.changedBefore(markedAt = 1_000, changedAt = null))
        assertFalse(TabRestore.changedBefore(markedAt = 1_000, changedAt = 900))
        val closes = TabRestore.SingleCloses()
        closes.closed("t1", changedBefore = true, turn = "close", byUserInput = false)
        assertEquals(true, closes.take("t1", lastChangeTurn = null))
    }

    /**
     * The restore runs inside the dispatch of the branch change, and Skein's own listener of that change may come after
     * it - so a change in the very turn of the EDT the tab closed in still makes the tab the restore's.
     */
    @Test
    fun aChangeInTheSameTurnAsTheCloseIsTheRestores() {
        val closes = TabRestore.SingleCloses()
        val turn = Any()
        closes.closed("t1", changedBefore = false, turn = turn, byUserInput = false)
        assertEquals(true, closes.take("t1", lastChangeTurn = turn))
    }

    /**
     * The user's middle click during a checkout, which then finishes within the wait - the change comes in a later turn,
     * so the close stays the user's, and the thread goes.
     */
    @Test
    fun aCloseBeforeTheChangeStaysAClose() {
        val closes = TabRestore.SingleCloses()
        closes.closed("t1", changedBefore = false, turn = "click", byUserInput = true)
        assertEquals(false, closes.take("t1", lastChangeTurn = "the change, later"))
        closes.closed("t2", changedBefore = false, turn = "click", byUserInput = true)
        assertEquals(false, closes.take("t2", lastChangeTurn = null))
    }

    /**
     * Only the event a close of the user's really comes from counts - the mouse button's release, on which the editor tab's
     * middle click, Shift+click and Shift+Alt+click close (`TabMouseListener.mouseReleased`), and a key press, on which a shortcut runs. The
     * dispatcher's current event is not restored after a nested event loop, so inside the restore it can be a stale mouse
     * move or click, which must not make the restore's close the user's.
     */
    @Test
    fun onlyAButtonReleaseOrAKeyPressIsTheUsersCloseInput() {
        assertTrue(TabRestore.closedByUsersInput(mouse = true, key = false, id = java.awt.event.MouseEvent.MOUSE_RELEASED))
        assertTrue(TabRestore.closedByUsersInput(mouse = false, key = true, id = java.awt.event.KeyEvent.KEY_PRESSED))
        for (id in listOf(
            java.awt.event.MouseEvent.MOUSE_MOVED, java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.MouseEvent.MOUSE_CLICKED,
            java.awt.event.MouseEvent.MOUSE_DRAGGED, java.awt.event.MouseEvent.MOUSE_ENTERED, java.awt.event.MouseEvent.MOUSE_EXITED,
        )) {
            assertFalse("mouse $id", TabRestore.closedByUsersInput(mouse = true, key = false, id = id))
        }
        assertFalse(TabRestore.closedByUsersInput(mouse = false, key = true, id = java.awt.event.KeyEvent.KEY_RELEASED))
        assertFalse(TabRestore.closedByUsersInput(mouse = false, key = true, id = java.awt.event.KeyEvent.KEY_TYPED))
        // an invocation event, as for Git's own runnables and the restore inside them
        assertFalse(TabRestore.closedByUsersInput(mouse = false, key = false, id = java.awt.event.InvocationEvent.INVOCATION_DEFAULT))
        assertFalse(TabRestore.closedByUsersInput(mouse = false, key = false, id = java.awt.event.MouseEvent.MOUSE_RELEASED))
    }

    /**
     * A middle click or a Shift+click in the second after a checkout succeeded, before the marks the restore did not use
     * come off - the branch change came before the close, but the close came from the user's own mouse or keys, which no
     * restore does, so it stays the user's close and the thread goes.
     */
    @Test
    fun aCloseByTheUsersInputIsTheUsersWhateverTheChangeSays() {
        val closes = TabRestore.SingleCloses()
        closes.closed("t1", changedBefore = true, turn = "click", byUserInput = true)
        assertEquals(false, closes.take("t1", lastChangeTurn = null))
        // nor does a change in that same event make it the restore's
        closes.closed("t2", changedBefore = false, turn = "click", byUserInput = true)
        assertEquals(false, closes.take("t2", lastChangeTurn = "click"))
    }

    /**
     * A drag's drop opens the file again in a coroutine of its own, after the turn of the close, and a busy IDE may take a
     * while. The tab turning up again cancels the look waiting for it, whenever that comes.
     */
    @Test
    fun aTabThatTurnsUpAgainCancelsTheLookAtItsClose() {
        val closes = TabRestore.SingleCloses()
        closes.closed("t1", changedBefore = false, turn = null, byUserInput = false)
        closes.closed("t2", changedBefore = false, turn = null, byUserInput = false)
        assertTrue(closes.seenAgain("t1"))
        assertEquals(null, closes.take("t1", lastChangeTurn = null))
        assertEquals(false, closes.take("t2", lastChangeTurn = null))
        // taken once, and a tab nobody waits for is no news
        assertEquals(null, closes.take("t2", lastChangeTurn = null))
        assertFalse(closes.seenAgain("t3"))
    }

    /** The look waits several seconds, long enough for a busy IDE to drop a dragged tab, while the mark keeps the terminal alive. */
    @Test
    fun theLookWaitsSeveralSeconds() {
        assertEquals(5_000L, TabRestore.SINGLE_CLOSE_WAIT_MILLIS)
    }

    /** A marked tab closed on its own whose terminal ended anyway was the user's close, and the thread goes. */
    @Test
    fun aMarkedTabClosedOnItsOwnWhoseTerminalDiedIsClosed() {
        assertEquals(Gone.CLOSED, TabRestore.gone(projectClosing = false, pastCheck = Close.END_IF_CLOSED))
    }

    /**
     * The IDE's restore clears the active editor area first, its windows taken down before its files close, and then the
     * floating editor windows, whose files close with their window still there. All of it in one turn of the EDT counts
     * as the clear.
     */
    @Test
    fun aClearStaysAClearForTheRestOfItsTurn() {
        assertTrue(TabRestore.wholeArea(soFar = false, windowsGone = true))
        assertTrue(TabRestore.wholeArea(soFar = true, windowsGone = false))
        assertFalse(TabRestore.wholeArea(soFar = false, windowsGone = false))
    }

    /**
     * The first past-check close of a clear ends every running thread tab the clear takes, together, under one deadline -
     * not those Skein closes or ends already, nor one the user closes, nor one Skein's mark keeps, nor one the clear does
     * not take (a tab in another window, or in the Terminal tool window).
     */
    @Test
    fun theFirstPastCheckCloseOfAClearEndsItsThreadsTogether() {
        val tabs = listOf(
            TabRestore.ClearTab("t1", inThisClear = true),
            TabRestore.ClearTab("t2", inThisClear = true),
            TabRestore.ClearTab("t3", inThisClear = false),
            TabRestore.ClearTab("t4", inThisClear = true, released = true),
            TabRestore.ClearTab("t5", inThisClear = true, ending = true),
            TabRestore.ClearTab("t6", inThisClear = true, askedByUser = true),
            TabRestore.ClearTab("t7", inThisClear = true, keptByMark = true),
        )
        assertEquals(listOf("t1", "t2"), TabRestore.endTogether(tabs))
    }

    /** The user's ×, Close, Close All, the menu's Close - the close check was asked and said yes, as today. */
    @Test
    fun aCloseTheCheckLetThroughIsTheUsersOwn() {
        assertEquals(Close.LET_GO, close(askedByUser = true))
        // also when Skein had marked the file - the user's close wins, and the mark comes off
        assertEquals(Close.LET_GO, close(reopenMark = true, markedBySkein = true, askedByUser = true))
    }

    @Test
    fun skeinsOwnCloseAndAnEndingUnderWayAreNeverReopened() {
        assertEquals(Close.LET_GO, close(released = true))
        assertEquals(Close.LET_GO, close(released = true, reopenMark = true, markedBySkein = true))
        assertEquals(Close.LET_GO, close(ending = true))
    }

    @Test
    fun aProjectThatClosesReopensNothing() {
        assertEquals(Close.LET_GO, close(closing = true))
        assertEquals(Close.LET_GO, close(closing = true, reopenMark = true, markedBySkein = true))
    }

    @Test
    fun onlyAReopenOrALookKeepsSkeinsMark() {
        assertTrue(TabRestore.keepsMark(Close.REOPEN_SAME))
        assertTrue(TabRestore.keepsMark(Close.END_IF_CLOSED))
        assertFalse(TabRestore.keepsMark(Close.LET_GO))
        assertFalse(TabRestore.keepsMark(Close.END_THEN_RESUME))
    }

    @Test
    fun aTerminalThatGoesWithoutAPastCheckCloseIsAClosedThread() {
        assertEquals(Gone.CLOSED, TabRestore.gone(projectClosing = false, pastCheck = null))
    }

    @Test
    fun aTerminalSkeinEndedBeforeTheCloseComesBackAsAResume() {
        assertEquals(Gone.BACK_AS_RESUME, TabRestore.gone(projectClosing = false, pastCheck = Close.END_THEN_RESUME))
    }

    /** A future IDE that no longer honours the mark - nothing could be hung up any more, and the thread still comes back. */
    @Test
    fun aMarkedTerminalThatDiedAnywayComesBackAsAResume() {
        assertEquals(Gone.TERMINAL_DIED, TabRestore.gone(projectClosing = false, pastCheck = Close.REOPEN_SAME))
    }

    @Test
    fun nothingComesBackWhileTheProjectCloses() {
        for (pastCheck in listOf(null, Close.REOPEN_SAME, Close.END_IF_CLOSED, Close.END_THEN_RESUME, Close.END_THEN_CLOSE)) {
            assertEquals(Gone.NOTHING, TabRestore.gone(projectClosing = true, pastCheck = pastCheck))
        }
    }

    @Test
    fun theSameTabOpensAgainOnlyWhileItIsInNeitherPlace() {
        assertTrue(TabRestore.reopensSame(TabPlaces.Where.MOVING, released = false))
        // a move opened it again already, a drag took it into the Terminal tool window, it is gone, or Skein closes it
        assertFalse(TabRestore.reopensSame(TabPlaces.Where.EDITOR, released = false))
        assertFalse(TabRestore.reopensSame(TabPlaces.Where.TERMINAL_WINDOW, released = false))
        assertFalse(TabRestore.reopensSame(TabPlaces.Where.GONE, released = false))
        assertFalse(TabRestore.reopensSame(null, released = false))
        assertFalse(TabRestore.reopensSame(TabPlaces.Where.MOVING, released = true))
    }

    /** Showing a never-started tab would start its shell, and the thread's claude with it. */
    @Test
    fun aNeverStartedTabComesBackUnselected() {
        assertFalse(TabRestore.selects(wasInFront = true, started = false))
        assertFalse(TabRestore.selects(wasInFront = false, started = false))
    }

    @Test
    fun theTabThatWasInFrontIsSelectedAgainAndNoOther() {
        assertTrue(TabRestore.selects(wasInFront = true, started = true))
        assertFalse(TabRestore.selects(wasInFront = false, started = true))
    }

    @Test
    fun onceTheBranchChangeSettledEveryMarkNoRestoreUsedComesOff() {
        val marked = mapOf("t1" to 1_000L, "t2" to 1_000L, "t3" to 1_000L)
        assertEquals(listOf("t1", "t3"), TabRestore.toLift(marked, awaitingReopen = setOf("t2"), changedAt = 1_200, now = 2_200))
    }

    /** A second checkout started while the first one settles - its fresh marks wait for its own restore. */
    @Test
    fun aMarkFromANewerBranchSwitchStays() {
        val marked = mapOf("t1" to 1_000L, "t2" to 1_500L)
        assertEquals(listOf("t1"), TabRestore.toLift(marked, emptySet(), changedAt = 1_200, now = 2_200))
    }

    /** A checkout that failed never says the branch changed, and its marks come off after ten minutes. */
    @Test
    fun aBranchThatNeverChangedLosesItsMarksOnceTheyAreOld() {
        val marked = mapOf("t1" to 1_000L, "t2" to 1_000L + TabRestore.MARK_LIMIT_MILLIS)
        assertEquals(emptyList<String>(), TabRestore.toLift(marked, emptySet(), changedAt = null, now = 1_000 + TabRestore.MARK_LIMIT_MILLIS - 1))
        assertEquals(listOf("t1"), TabRestore.toLift(marked, emptySet(), changedAt = null, now = 1_000 + TabRestore.MARK_LIMIT_MILLIS))
        // one the restore closed waits for its reopen, which takes the mark off itself
        assertEquals(emptyList<String>(), TabRestore.toLift(marked, setOf("t1"), changedAt = null, now = 1_000 + TabRestore.MARK_LIMIT_MILLIS))
        assertEquals(10 * 60_000L, TabRestore.MARK_LIMIT_MILLIS)
    }
}
