package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.terminal.TabPlaces.Where
import org.junit.Assert.assertEquals
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
}
