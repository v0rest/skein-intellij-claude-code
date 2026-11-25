package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.terminal.TabPlaces.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a thread's terminal tab is: the Terminal tool window, the editor, on its way between them, or gone. Only a tab
 * whose content the IDE disposed is gone; an editor file closing on its own means nothing, since a drag into the
 * Terminal tool window closes it too and the terminal goes on there.
 */
class TabPlacesTest {
    private fun where(projectClosing: Boolean = false, disposed: Boolean = false, inTerminalWindow: Boolean = false, editorFileOpen: Boolean = false) =
        TabPlaces.where(projectClosing, disposed, inTerminalWindow, editorFileOpen)

    @Test
    fun aTabWhoseContentWasDisposedIsGone() = assertEquals(Where.GONE, where(disposed = true))

    @Test
    fun aDisposedTabIsGoneWhateverStillListsIt() = assertEquals(Where.GONE, where(disposed = true, inTerminalWindow = true, editorFileOpen = true))

    /** The reported bug: the editor file closed because the tab was dragged into the Terminal tool window. */
    @Test
    fun aTabInNeitherPlaceThatLivesIsMoving() = assertEquals(Where.MOVING, where())

    @Test
    fun aTabTheTerminalWindowListsIsThere() = assertEquals(Where.TERMINAL_WINDOW, where(inTerminalWindow = true))

    @Test
    fun aTabAnOpenEditorFileShowsIsInTheEditor() = assertEquals(Where.EDITOR, where(editorFileOpen = true))

    /** A tab is in one place; should both ever answer, the Terminal tool window, which lists the tab itself, wins. */
    @Test
    fun theTerminalWindowWinsOverAnEditorFile() = assertEquals(Where.TERMINAL_WINDOW, where(inTerminalWindow = true, editorFileOpen = true))

    @Test
    fun nothingIsDecidedWhileTheProjectCloses() = assertNull(where(projectClosing = true, disposed = true))

    /**
     * A move into the editor that failed is judged by where the tab is then - the action may have taken it out of the
     * Terminal tool window before it failed. Only a tab the tool window still lists stays there, and only that one is
     * said to stay there - the log once claimed it for a tab that sat in the editor.
     */
    @Test
    fun aFailedMoveLeavesTheTabWhereItIs() {
        assertEquals(TabPlaces.AfterFailedMove.STAYS, TabPlaces.afterFailedMove(Where.TERMINAL_WINDOW))
        assertEquals(TabPlaces.AfterFailedMove.IN_EDITOR, TabPlaces.afterFailedMove(Where.EDITOR))
        assertEquals(TabPlaces.AfterFailedMove.LOOKS_AGAIN, TabPlaces.afterFailedMove(Where.MOVING))
        assertEquals(TabPlaces.AfterFailedMove.GONE, TabPlaces.afterFailedMove(Where.GONE))
    }

    /** Where the tab is could not be told - the project closing, or the look failing - and nothing looks again then. */
    @Test
    fun aFailedMoveWhoseTabCannotBeFoundSaysSo() {
        val after = TabPlaces.afterFailedMove(null)
        assertEquals(TabPlaces.AfterFailedMove.UNTOLD, after)
        assertFalse(after.words, after.words.contains("looks for"))
    }

    @Test
    fun onlyATabStillInTheTerminalWindowIsSaidToStayThere() {
        for (after in TabPlaces.AfterFailedMove.entries) {
            assertEquals(after.name, after == TabPlaces.AfterFailedMove.STAYS, after.words.contains("stays in the Terminal tool window"))
        }
    }
}
