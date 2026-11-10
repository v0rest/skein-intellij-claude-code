package io.github.v0rest.skein.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.MenuElement

class RowSelectionTest {
    @Test
    fun aSelectionStaysOnlyWhileTheListIsInUse() {
        assertEquals(setOf("t1"), RowSelection.restored(setOf("t1"), listInUse = true))
        assertEquals(setOf("t1", "t2"), RowSelection.restored(setOf("t1", "t2"), listInUse = true))
        assertEquals(emptySet<String>(), RowSelection.restored(setOf("t1"), listInUse = false))
        // a drag across rows leaves several selected; once the user is elsewhere they go like a single one
        assertEquals(emptySet<String>(), RowSelection.restored(setOf("t1", "t2"), listInUse = false))
        assertEquals(emptySet<String>(), RowSelection.restored(emptySet(), listInUse = true))
    }

    @Test
    fun aPlainClickOpensWhenPressAndReleaseLandOnTheSameThread() {
        assertEquals(RowSelection.Release.Open("t1"), RowSelection.onRelease(pressed = "t1", released = "t1", clickCount = 1, opened = null))
        // the release after a small drag may come with no click counted
        assertEquals(RowSelection.Release.Open("t1"), RowSelection.onRelease(pressed = "t1", released = "t1", clickCount = 0, opened = null))
        // what an earlier click opened does not matter to a single one
        assertEquals(RowSelection.Release.Open("t1"), RowSelection.onRelease(pressed = "t1", released = "t1", clickCount = 1, opened = "t2"))
    }

    /** The first click opened the thread and took the focus to its tab; the second press took it back to the list. */
    @Test
    fun aDoubleClickOnTheSameThreadOpensItAgain() {
        assertEquals(RowSelection.Release.Open("t1"), RowSelection.onRelease(pressed = "t1", released = "t1", clickCount = 2, opened = "t1"))
        assertEquals(RowSelection.Release.Open("t1"), RowSelection.onRelease(pressed = "t1", released = "t1", clickCount = 3, opened = "t1"))
    }

    /**
     * Opening a thread can move its row (a finished turn marked viewed sorts below older idle threads, a filter hides
     * it), so the second click of a double click may land on another thread, which it must not open.
     */
    @Test
    fun aDoubleClickWhoseRowChangedBetweenTheClicksOnlyClears() {
        assertEquals(RowSelection.Release.Clear, RowSelection.onRelease(pressed = "t2", released = "t2", clickCount = 2, opened = "t1"))
        // the first click opened nothing (it landed on no row, or on a row it dragged off)
        assertEquals(RowSelection.Release.Clear, RowSelection.onRelease(pressed = "t2", released = "t2", clickCount = 2, opened = null))
    }

    @Test
    fun aClickOnNoRowClears() {
        assertEquals(RowSelection.Release.Clear, RowSelection.onRelease(pressed = null, released = null, clickCount = 1, opened = null))
        assertEquals(RowSelection.Release.Clear, RowSelection.onRelease(pressed = null, released = null, clickCount = 2, opened = "t1"))
    }

    @Test
    fun aDragFromOneThreadToAnotherKeepsWhatItSelected() {
        assertEquals(RowSelection.Release.Keep, RowSelection.onRelease(pressed = "t1", released = "t2", clickCount = 1, opened = null))
        assertEquals(RowSelection.Release.Keep, RowSelection.onRelease(pressed = "t1", released = null, clickCount = 1, opened = null))
        assertEquals(RowSelection.Release.Keep, RowSelection.onRelease(pressed = null, released = "t2", clickCount = 1, opened = null))
    }

    /** The History opens a session on a double click: a single click only selects the row. */
    @Test
    fun inTheHistoryASingleClickOnlySelects() {
        val history = RowSelection.Opening.DOUBLE_CLICK
        assertEquals(RowSelection.Release.Select("s1"), RowSelection.onRelease(pressed = "s1", released = "s1", clickCount = 1, opened = null, opening = history))
        // the release after a small drag may come with no click counted
        assertEquals(RowSelection.Release.Select("s1"), RowSelection.onRelease(pressed = "s1", released = "s1", clickCount = 0, opened = null, opening = history))
        // what an earlier click selected does not make a single click open
        assertEquals(RowSelection.Release.Select("s1"), RowSelection.onRelease(pressed = "s1", released = "s1", clickCount = 1, opened = "s1", opening = history))
    }

    @Test
    fun inTheHistoryADoubleClickOnTheSameRowOpensIt() {
        val history = RowSelection.Opening.DOUBLE_CLICK
        assertEquals(RowSelection.Release.Open("s1"), RowSelection.onRelease(pressed = "s1", released = "s1", clickCount = 2, opened = "s1", opening = history))
    }

    /**
     * A refresh can move the rows between the two clicks (a session started elsewhere, a pin), so the second click may
     * land on another session, which it must not resume; it leaves that row selected, as a single click would.
     */
    @Test
    fun inTheHistoryADoubleClickWhoseRowChangedBetweenTheClicksDoesNotOpen() {
        val history = RowSelection.Opening.DOUBLE_CLICK
        assertEquals(RowSelection.Release.Keep, RowSelection.onRelease(pressed = "s2", released = "s2", clickCount = 2, opened = "s1", opening = history))
        // the first click selected nothing (it landed on no row, or on a row it dragged off)
        assertEquals(RowSelection.Release.Keep, RowSelection.onRelease(pressed = "s2", released = "s2", clickCount = 2, opened = null, opening = history))
    }

    @Test
    fun inTheHistoryAClickOnNoRowClearsAndADragKeeps() {
        val history = RowSelection.Opening.DOUBLE_CLICK
        assertEquals(RowSelection.Release.Clear, RowSelection.onRelease(pressed = null, released = null, clickCount = 1, opened = null, opening = history))
        assertEquals(RowSelection.Release.Clear, RowSelection.onRelease(pressed = null, released = null, clickCount = 2, opened = "s1", opening = history))
        assertEquals(RowSelection.Release.Keep, RowSelection.onRelease(pressed = "s1", released = "s2", clickCount = 1, opened = null, opening = history))
        assertEquals(RowSelection.Release.Keep, RowSelection.onRelease(pressed = "s1", released = "s2", clickCount = 2, opened = "s1", opening = history))
    }

    /** What the row remembers for the next click: the one it opened, and in the History also the one it selected. */
    @Test
    fun theRowAReleaseActedOnIsWhatTheNextClickComparesWith() {
        assertEquals("s1", RowSelection.actedOn(RowSelection.Release.Open("s1")))
        assertEquals("s1", RowSelection.actedOn(RowSelection.Release.Select("s1")))
        assertEquals(null, RowSelection.actedOn(RowSelection.Release.Keep))
        assertEquals(null, RowSelection.actedOn(RowSelection.Release.Clear))
    }

    @Test
    fun onlyAMenuOpenedOnTheListCountsAsItsOwn() {
        val list = JList<String>()
        val own = JPopupMenu().apply { invoker = list }
        val elsewhere = JPopupMenu().apply { invoker = JPanel() }
        assertTrue(RowSelection.ownMenuOpen(arrayOf(own), list))
        // a submenu (Color) keeps the list's popup first in the path
        assertTrue(RowSelection.ownMenuOpen(arrayOf<MenuElement>(own, JMenu("Color"), JPopupMenu()), list))
        // a right click on the terminal opens a menu too, but not the list's
        assertFalse(RowSelection.ownMenuOpen(arrayOf(elsewhere), list))
        assertFalse(RowSelection.ownMenuOpen(emptyArray<MenuElement>(), list))
    }
}
