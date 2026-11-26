package io.github.v0rest.skein.ui

import java.awt.Component
import javax.swing.JPopupMenu
import javax.swing.MenuElement

/**
 * The selection rules of the thread list, the History list and the Worktrees section, whose rows they know by id (a
 * thread id, a session id, a folder). In the thread list a plain click opens the row and leaves it selected, and the
 * selection stays until a click on another row moves it or a click on no row clears it ([Keeping.CLICKED]); in the
 * History a plain click only selects the row and a double click opens it ([Opening]), opening leaves no row selected,
 * and a selection lasts only while the list is in use, that is while it has focus or its own menu is open
 * ([Keeping.WHILE_IN_USE]). In every list nothing lights up below the rows: a press or a drag there selects none, where
 * Swing would select the row nearest to the mouse, and a right click there clears the selection.
 */
object RowSelection {
    /** How long a list keeps its selection. */
    enum class Keeping {
        /** While the list has focus or its own menu is open; opening a row leaves none selected. The History, the Worktrees section. */
        WHILE_IN_USE,

        /**
         * The row a click opened stays selected, also once the focus went on to its tab, until a click on another row
         * moves the selection or a click on no row clears it; a drag, or any other gesture that is no click, goes back to
         * it ([clickedAfter]). The thread list.
         */
        CLICKED,
    }

    /**
     * What a refresh selects again: the selection, one row or several, while the list is in use ([listInUse]) or always
     * in a list that keeps what was clicked ([Keeping.CLICKED]); else nothing.
     */
    fun restored(selectedIds: Set<String>, listInUse: Boolean, keeping: Keeping = Keeping.WHILE_IN_USE): Set<String> =
        if (listInUse || keeping == Keeping.CLICKED) selectedIds else emptySet()

    /** Whether the focus going for good (no menu of the list's own open) clears the selection: only where it lasts while in use. */
    fun clearsOnFocusLoss(keeping: Keeping): Boolean = keeping == Keeping.WHILE_IN_USE

    /** Which click opens a row: a single one in the thread list, a double one in the History. */
    enum class Opening { CLICK, DOUBLE_CLICK }

    /** What a plain left-button release does to the list (no Shift, Cmd or Ctrl; those select rows for the menus). */
    sealed interface Release {
        /** Open the row: a click. It stays selected or not as the list keeps its selection ([Keeping]). */
        data class Open(val id: String) : Release

        /** Leave the row the click selected, the first click of a double click that would open it. */
        data class Select(val id: String) : Release

        /**
         * Clear the selection only: a click on no row, or in the thread list a double click whose second click landed
         * on another row.
         */
        object Clear : Release

        /**
         * Leave what the mouse selected: it went from one row to another, a drag across rows, or in the History the
         * second click of a double click landed on another row, which it selects as a single click would.
         */
        object Keep : Release
    }

    /**
     * Decides on the release, not on the click: AWT sends no click when the mouse moved a little between press and
     * release, and the row the press selected would stay highlighted. [pressed] and [released] are the rows under the
     * mouse then (null on no row), by id, so a refresh in between cannot swap the row. [clickCount] is the release's:
     * below 2 it opens, as the release after a small drag may count no click at all. The second release of a double
     * click opens the row again only when it is the one the release before it opened ([opened], null when that one
     * opened nothing): bringing a tab forward twice is harmless and sends on to the tab the focus the second press took
     * back to the list, but opening a row can move it (a finished turn marked viewed sorts below older idle threads, a
     * filter hides it), and the second click must not open whatever row came under the mouse.
     *
     * With [Opening.DOUBLE_CLICK] (the History) a release below 2 clicks only selects its row, and [opened] is the row
     * the release before selected or opened ([actedOn]): the second click of a double click opens only the row the
     * first one landed on, since a refresh in between can move the rows, and on another row it selects that one instead.
     */
    fun onRelease(pressed: String?, released: String?, clickCount: Int, opened: String?, opening: Opening = Opening.CLICK): Release = when {
        pressed != released -> Release.Keep
        pressed == null -> Release.Clear
        opening == Opening.DOUBLE_CLICK && clickCount < 2 -> Release.Select(pressed)
        clickCount >= 2 && pressed != opened -> if (opening == Opening.DOUBLE_CLICK) Release.Keep else Release.Clear
        else -> Release.Open(pressed)
    }

    /**
     * The row a list that keeps what was clicked ([Keeping.CLICKED]) has selected once a plain release is done: the row
     * it opened, none after a click on no row ([pressed] null, and so the release on none either), and for anything else
     * - a drag that ended on another row or below the rows, the second click of a double click that landed on another
     * row - the row the user's last click selected ([clicked]), so only a click changes what is selected.
     */
    fun clickedAfter(release: Release, pressed: String?, clicked: String?): String? = when (release) {
        is Release.Open -> release.id
        Release.Clear -> if (pressed == null) null else clicked
        Release.Keep, is Release.Select -> clicked
    }

    /** The row a release opened or selected, which the next release passes as `opened`; null for one that did neither. */
    fun actedOn(release: Release): String? = when (release) {
        is Release.Open -> release.id
        is Release.Select -> release.id
        Release.Clear, Release.Keep -> null
    }

    /**
     * Whether the open Swing menu ([path], MenuSelectionManager's selected path) was opened on [list]: a right click
     * on another component (the terminal) opens a menu too, which must not keep the list's selection.
     */
    fun ownMenuOpen(path: Array<out MenuElement>, list: Component): Boolean = (path.firstOrNull() as? JPopupMenu)?.invoker === list
}
