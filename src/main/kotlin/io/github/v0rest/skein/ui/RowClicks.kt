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
 * History; [opening] says which click opens a row and [keeping] how long the selection lasts. In the thread list a plain
 * left release on the row it was pressed on opens that row ([open]) and leaves it selected (the second click of a double
 * click only when it lands on the row the first one opened), and any other plain left gesture leaves the row the last
 * click selected ([RowSelection.clickedAfter]); in the History a release only selects the row, and the second click of a
 * double click on that same row opens it. Enter opens the lead row in both. Nothing lights up below the rows: a plain
 * left press there, or a plain drag into that space, clears the selection, where Swing would select the row nearest to
 * the mouse. A right click selects the row under it and focuses the list, so the row lasts while its menu is in use; a
 * right click below the rows clears the selection, so its menu acts on no row. Where the selection lasts only while the
 * list is in use, it goes once the user is elsewhere (a permanent focus loss, with no menu of the list's own open).
 * [install] it before the list's popup menu, so a right click has selected its row when the menu reads the selection.
 * EDT only.
 */
internal class RowClicks<T>(
    private val list: JList<T>,
    private val idOf: (T) -> String,
    private val opening: RowSelection.Opening = RowSelection.Opening.CLICK,
    private val keeping: RowSelection.Keeping = RowSelection.Keeping.WHILE_IN_USE,
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

    /**
     * In a list that keeps what was clicked ([RowSelection.Keeping.CLICKED]), the row the user's last click selected, by
     * id, or null after a click below the rows; a gesture that is no click goes back to it.
     */
    private var clicked: String? = null

    /** The list has focus, or its own right-click menu is open ([ownMenuOpen]). */
    fun inUse(): Boolean = list.hasFocus() || ownMenuOpen()

    /** The ids a refresh of the rows selects again ([RowSelection.restored]). */
    fun kept(): Set<String> = RowSelection.restored(list.selectedValuesList.mapTo(HashSet(), idOf), listInUse = inUse(), keeping = keeping)

    fun install() {
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    // only a plain press can become a click that opens; Shift and Cmd/Ctrl select rows for the menus
                    plainPress = isPlainLeft(e)
                    pressedId = if (plainPress) rowAt(e)?.let(::idAt) else null
                    // a right click acts on the row under it, as a file list does
                    if (!SwingUtilities.isRightMouseButton(e) && !e.isPopupTrigger) return
                    val index = rowAt(e)
                    if (index == null) {
                        // below the rows a right click, like a left one, leaves no row selected for the menu to act on
                        list.clearSelection()
                        clicked = null
                        return
                    }
                    if (!list.isSelectedIndex(index)) {
                        list.selectedIndex = index
                        clicked = idAt(index)
                    }
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
                    when {
                        release is RowSelection.Release.Open -> index?.let { openRow(list.model.getElementAt(it)) }
                        keeping == RowSelection.Keeping.CLICKED -> {
                            clicked = RowSelection.clickedAfter(release, pressedId, clicked)
                            select(clicked)
                        }
                        release == RowSelection.Release.Clear -> list.clearSelection()
                        // the press selected the row, as a click in any list does
                        else -> Unit
                    }
                }
            },
        )
        // Swing selects the row nearest to the mouse when it is pressed or dragged below the rows. This listener clears
        // that again, so it must run after Swing's own: it comes after the list's UI here, and a new look and feel, whose
        // UI adds its listeners after it, has it added again behind them. The listener above stays where it is, before
        // the list's popup menu.
        val belowTheRows = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // a Ctrl+click is the macOS right click, which Swing still takes for a left press and selects with
                if ((plainPress || e.isPopupTrigger) && rowAt(e) == null) list.clearSelection()
            }

            override fun mouseDragged(e: MouseEvent) {
                if (plainPress && rowAt(e) == null) list.clearSelection()
            }
        }
        list.addMouseListener(belowTheRows)
        list.addMouseMotionListener(belowTheRows)
        list.addPropertyChangeListener("UI") {
            list.removeMouseListener(belowTheRows)
            list.removeMouseMotionListener(belowTheRows)
            list.addMouseListener(belowTheRows)
            list.addMouseMotionListener(belowTheRows)
        }
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
                    // rows were selected for their menu, by the arrow keys or a drag; once the user is elsewhere, let them
                    // go, unless the list keeps the row that was clicked, which the focus leaves for the row's own tab
                    if (RowSelection.clearsOnFocusLoss(keeping) && !e.isTemporary && !ownMenuOpen()) list.clearSelection()
                }
            },
        )
    }

    /** The list's own right-click menu is open, whose actions read the selection; another component's menu does not count. */
    private fun ownMenuOpen(): Boolean = RowSelection.ownMenuOpen(MenuSelectionManager.defaultManager().selectedPath, list)

    /**
     * Opens [row] as a click does (also for a menu's Open and Enter). In a list that keeps what was clicked
     * ([RowSelection.Keeping.CLICKED]) the row is then the one selected; elsewhere opening is not selecting, so no row
     * stays highlighted after it.
     */
    fun openRow(row: T) {
        open(row)
        if (keeping == RowSelection.Keeping.CLICKED) {
            clicked = idOf(row)
            select(clicked)
        } else {
            list.clearSelection()
        }
    }

    /** Selects the row with [id] alone, wherever opening it may have moved it; none for null or a row that is gone. */
    private fun select(id: String?) {
        val index = id?.let { (0 until list.model.size).firstOrNull { idAt(it) == id } }
        if (index == null) list.clearSelection() else list.selectedIndex = index
    }

    private fun idAt(index: Int): String = idOf(list.model.getElementAt(index))

    /** The left button with no Shift, Cmd or Ctrl: those select rows for the menus (Ctrl+click is a right click on macOS). */
    private fun isPlainLeft(e: MouseEvent): Boolean = e.button == MouseEvent.BUTTON1 && !e.isShiftDown && !e.isMetaDown && !e.isControlDown

    private fun rowAt(e: MouseEvent): Int? {
        val index = list.locationToIndex(e.point)
        return index.takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(e.point) == true }
    }
}
