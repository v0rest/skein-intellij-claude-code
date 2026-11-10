package io.github.v0rest.skein.ui

import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities

/**
 * [RowSelection]'s rules on a Swing list whose rows are known by id ([idOf]), the same for the thread list and the
 * History; [opening] says which click opens a row. In the thread list a plain left release on the row it was pressed on
 * opens that row ([open]) and clears the selection (the second click of a double click only when it lands on the row
 * the first one opened); in the History it only selects the row, and the second click of a double click on that same
 * row opens it. Enter opens the lead row in both. A right click selects the row under it and focuses the list, so the
 * row lasts while its menu is in use; once the user is elsewhere (a permanent focus loss, with no menu of the list's
 * own open) the selection goes. [install] it before the list's popup menu, so a right click has selected its row when
 * the menu reads the selection. EDT only.
 */
internal class RowClicks<T>(
    private val list: JList<T>,
    private val idOf: (T) -> String,
    private val opening: RowSelection.Opening = RowSelection.Opening.CLICK,
    private val open: (T) -> Unit,
) {
    /** Whether the last press on the list was a plain left-button one, which its release may turn into opening. */
    private var plainPress = false

    /** The row under that press, by id (null on no row), so a refresh before the release cannot swap the row. */
    private var pressedId: String? = null

    /**
     * The row the last plain release opened or, in the History, selected, by id (null when it did neither): the second
     * click of a double click opens only that one ([RowSelection.actedOn]).
     */
    private var actedId: String? = null

    /** The list has focus, or its own right-click menu is open ([ownMenuOpen]). */
    fun inUse(): Boolean = list.hasFocus() || ownMenuOpen()

    /** The ids a refresh of the rows selects again ([RowSelection.restored]): the selection while the list is in use, else none. */
    fun kept(): Set<String> = RowSelection.restored(list.selectedValuesList.mapTo(HashSet(), idOf), listInUse = inUse())

    fun install() {
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    // only a plain press can become a click that opens; Shift and Cmd/Ctrl select rows for the menus
                    plainPress = isPlainLeft(e)
                    pressedId = if (plainPress) rowAt(e)?.let(::idAt) else null
                    // a right click acts on the row under it, as a file list does
                    if (!SwingUtilities.isRightMouseButton(e) && !e.isPopupTrigger) return
                    val index = rowAt(e) ?: return
                    if (!list.isSelectedIndex(index)) list.selectedIndex = index
                    // Swing does not focus a list on a right click; with focus the row lasts while its menu is in use
                    list.requestFocusInWindow()
                }

                override fun mouseReleased(e: MouseEvent) {
                    // on the release, not the click: AWT sends no click when the mouse moved a little in between
                    if (!plainPress || !isPlainLeft(e)) return
                    plainPress = false
                    val index = rowAt(e)
                    val release = RowSelection.onRelease(pressedId, index?.let(::idAt), e.clickCount, actedId, opening)
                    actedId = RowSelection.actedOn(release)
                    when (release) {
                        is RowSelection.Release.Open -> index?.let { openRow(list.model.getElementAt(it)) }
                        RowSelection.Release.Clear -> list.clearSelection()
                        // the press selected the row, as a click in any list does
                        RowSelection.Release.Keep, is RowSelection.Release.Select -> Unit
                    }
                }
            },
        )
        list.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode != KeyEvent.VK_ENTER) return
                    list.selectedValue?.let(::openRow)
                }
            },
        )
        list.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) {
                    // rows were selected for their menu, by the arrow keys or a drag; once the user is elsewhere, let them go
                    if (!e.isTemporary && !ownMenuOpen()) list.clearSelection()
                }
            },
        )
    }

    /** The list's own right-click menu is open, whose actions read the selection; another component's menu does not count. */
    private fun ownMenuOpen(): Boolean = RowSelection.ownMenuOpen(MenuSelectionManager.defaultManager().selectedPath, list)

    /** Opens [row] as a click does (also for a menu's Open): opening is not selecting, so no row stays highlighted after it. */
    fun openRow(row: T) {
        open(row)
        list.clearSelection()
    }

    private fun idAt(index: Int): String = idOf(list.model.getElementAt(index))

    /** The left button with no Shift, Cmd or Ctrl: those select rows for the menus (Ctrl+click is a right click on macOS). */
    private fun isPlainLeft(e: MouseEvent): Boolean = e.button == MouseEvent.BUTTON1 && !e.isShiftDown && !e.isMetaDown && !e.isControlDown

    private fun rowAt(e: MouseEvent): Int? {
        val index = list.locationToIndex(e.point)
        return index.takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(e.point) == true }
    }
}
