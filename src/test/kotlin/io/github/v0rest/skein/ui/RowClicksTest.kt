package io.github.v0rest.skein.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.event.MouseInputAdapter
import javax.swing.event.MouseInputListener
import javax.swing.plaf.basic.BasicListUI

/**
 * Row clicks on a Swing list whose UI selects the row nearest to the mouse, below the rows too, as Swing's own does: what
 * a press, a drag and a release leave selected, in the thread list and in the History, and what the list's popup menu
 * sees on a right click.
 */
class RowClicksTest {
    /**
     * Swing's list UI with its mouse handling cut down to the part that matters here, which also runs headless (the real
     * one asks the toolkit for the menu shortcut key): a left press or a drag selects the row nearest to the mouse.
     * Installed the way the real one is, so its listeners come before or after Skein's as a look and feel's would.
     */
    private class NearestRowUi : BasicListUI() {
        override fun createMouseInputListener(): MouseInputListener = object : MouseInputAdapter() {
            override fun mousePressed(e: MouseEvent) = selectNearest(e)

            override fun mouseDragged(e: MouseEvent) = selectNearest(e)

            private fun selectNearest(e: MouseEvent) {
                if (e.id == MouseEvent.MOUSE_PRESSED && !SwingUtilities.isLeftMouseButton(e)) return
                val index = list.locationToIndex(e.point)
                if (index >= 0) list.selectedIndex = index
            }
        }
    }

    private val opened = mutableListOf<String>()

    /** What the list's popup menu saw selected on each right click, as ThreadMenu reads it. */
    private val menuSaw = mutableListOf<List<String>>()

    private val model = DefaultListModel<String>().apply {
        addElement("t1")
        addElement("t2")
    }

    /** Two rows of 20 px at the top of a 200 px list, empty space below them, and a popup menu installed after the clicks. */
    private fun list(keeping: RowSelection.Keeping, opening: RowSelection.Opening = RowSelection.Opening.CLICK, open: (String) -> Unit = { opened += it }): JList<String> {
        val list = JList(model).apply {
            setUI(NearestRowUi())
            fixedCellHeight = 20
            fixedCellWidth = 200
            setSize(200, 200)
        }
        RowClicks(list, { it }, opening, keeping, open).install()
        // as PopupHandler does: on macOS the press is the popup trigger, and the menu is built right then
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) menuSaw += list.selectedValuesList
                }
            },
        )
        return list
    }

    private fun press(list: JList<String>, y: Int, clicks: Int = 1) =
        list.dispatchEvent(MouseEvent(list, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, 50, y, clicks, false, MouseEvent.BUTTON1))

    private fun drag(list: JList<String>, y: Int) =
        list.dispatchEvent(MouseEvent(list, MouseEvent.MOUSE_DRAGGED, 0L, InputEvent.BUTTON1_DOWN_MASK, 51, y, 0, false, MouseEvent.NOBUTTON))

    private fun release(list: JList<String>, y: Int, clicks: Int = 1) =
        list.dispatchEvent(MouseEvent(list, MouseEvent.MOUSE_RELEASED, 0L, 0, 50, y, clicks, false, MouseEvent.BUTTON1))

    private fun click(list: JList<String>, y: Int, clicks: Int = 1) {
        press(list, y, clicks)
        release(list, y, clicks)
    }

    /** The macOS right click: the left button with Ctrl, a popup trigger that Swing still takes for a left press. */
    private fun ctrlPress(list: JList<String>, y: Int) = list.dispatchEvent(
        MouseEvent(list, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK or InputEvent.CTRL_DOWN_MASK, 50, y, 1, true, MouseEvent.BUTTON1),
    )

    private fun rightPress(list: JList<String>, y: Int) =
        list.dispatchEvent(MouseEvent(list, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON3_DOWN_MASK, 50, y, 1, true, MouseEvent.BUTTON3))

    /** The focus going for good, as when a click sent it on to the thread's tab. */
    private fun loseFocus(list: JList<String>) = list.focusListeners.forEach { it.focusLost(FocusEvent(list, FocusEvent.FOCUS_LOST, false)) }

    @Test
    fun inTheThreadListTheClickedRowStaysSelectedAfterTheFocusLeft() {
        val list = list(RowSelection.Keeping.CLICKED)
        click(list, 30)
        assertEquals(listOf("t2"), opened)
        assertEquals(1, list.selectedIndex)
        loseFocus(list)
        assertEquals(1, list.selectedIndex)
    }

    /** Below the rows Swing would select the last one; the press selects none, and the release opens nothing. */
    @Test
    fun aClickBelowTheRowsClearsTheClickedRowAndHighlightsNoOther() {
        val list = list(RowSelection.Keeping.CLICKED)
        click(list, 10)
        assertEquals(0, list.selectedIndex)
        press(list, 150)
        assertEquals("no row may light up while the mouse is down below the rows", -1, list.selectedIndex)
        release(list, 150)
        assertEquals(-1, list.selectedIndex)
        assertEquals(listOf("t1"), opened)
    }

    /** A trackpad moves a little while pressed: a drag below the rows lights up nothing either. */
    @Test
    fun aDragBelowTheRowsLightsUpNoRow() {
        val list = list(RowSelection.Keeping.CLICKED)
        press(list, 150)
        drag(list, 151)
        assertEquals(-1, list.selectedIndex)
        release(list, 151)
        assertEquals(-1, list.selectedIndex)
        assertEquals(emptyList<String>(), opened)
    }

    /** A press on a row dragged off it, below the rows or onto another row, is no click: the clicked row stays the selected one. */
    @Test
    fun inTheThreadListADragThatIsNoClickGoesBackToTheClickedRow() {
        val list = list(RowSelection.Keeping.CLICKED)
        click(list, 10)
        press(list, 30)
        drag(list, 150)
        assertEquals(-1, list.selectedIndex)
        release(list, 150)
        assertEquals(0, list.selectedIndex)

        press(list, 150)
        drag(list, 30)
        release(list, 30)
        assertEquals(0, list.selectedIndex)
        assertEquals(listOf("t1"), opened)
    }

    /** Opening can move the row (a finished turn marked seen sorts lower); the row stays selected where it went. */
    @Test
    fun theOpenedRowStaysSelectedWhereOpeningMovedIt() {
        val list = list(RowSelection.Keeping.CLICKED) { id ->
            opened += id
            model.removeElement(id)
            model.add(0, id)
        }
        click(list, 30)
        assertEquals(listOf("t2"), opened)
        assertEquals("t2", list.selectedValue)
        assertEquals(1, list.selectedIndices.size)
    }

    /** The first click opened a row that then moved; the second one lands on another row and leaves the opened one selected. */
    @Test
    fun aDoubleClickWhoseRowMovedLeavesTheOpenedRowSelected() {
        val list = list(RowSelection.Keeping.CLICKED) { id ->
            opened += id
            model.removeElement(id)
            model.addElement(id)
        }
        click(list, 10, clicks = 1)
        click(list, 10, clicks = 2)
        assertEquals(listOf("t1"), opened)
        assertEquals("t1", list.selectedValue)
    }

    @Test
    fun inTheHistoryAPressBelowTheRowsSelectsNoneAndTheSelectionStillGoesWithTheFocus() {
        val list = list(RowSelection.Keeping.WHILE_IN_USE, RowSelection.Opening.DOUBLE_CLICK)
        press(list, 150)
        assertEquals(-1, list.selectedIndex)
        release(list, 150)
        click(list, 10)
        assertEquals("a single click only selects in the History", emptyList<String>(), opened)
        assertEquals(0, list.selectedIndex)
        loseFocus(list)
        assertEquals(-1, list.selectedIndex)
    }

    /** A new look and feel (a theme switch) installs Swing's mouse handling again, after Skein's; nothing lights up below the rows still. */
    @Test
    fun aPressOrADragBelowTheRowsSelectsNoneAfterTheLookAndFeelChanged() {
        val list = list(RowSelection.Keeping.CLICKED)
        list.setUI(NearestRowUi())
        click(list, 10)
        assertEquals(0, list.selectedIndex)
        press(list, 150)
        assertEquals(-1, list.selectedIndex)
        drag(list, 151)
        assertEquals(-1, list.selectedIndex)
        release(list, 151)
    }

    /**
     * The menu acts on what a right click selected, the row under it, and on no row below the rows, also after a theme
     * switch put Swing's listeners again: the clicks stay before the menu.
     */
    @Test
    fun theMenuSeesTheRightClickedRowAndNoneBelowTheRowsAlsoAfterTheLookAndFeelChanged() {
        val list = list(RowSelection.Keeping.CLICKED)
        click(list, 10)
        list.setUI(NearestRowUi())
        rightPress(list, 30)
        rightPress(list, 150)
        assertEquals(listOf(listOf("t2"), emptyList()), menuSaw)
        assertEquals(-1, list.selectedIndex)
    }

    /** After a theme switch Swing's press comes after the menu's; a Ctrl+click below the rows still leaves no row lit. */
    @Test
    fun aCtrlClickBelowTheRowsLeavesNoRowLitAfterTheLookAndFeelChanged() {
        val list = list(RowSelection.Keeping.CLICKED)
        click(list, 10)
        list.setUI(NearestRowUi())
        ctrlPress(list, 150)
        assertEquals(listOf(emptyList<String>()), menuSaw)
        assertEquals(-1, list.selectedIndex)
    }
}
