package io.github.v0rest.skein.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.threads.DisplayNames
import java.awt.Component
import java.awt.Font
import javax.swing.JList
import javax.swing.ListCellRenderer

/** One row of the History list: the session with its marks, its folder or worktree [label] ([HistoryLabels.secondary]), and whether a live claude holds it. */
internal class HistoryItem(val row: HistoryRow, val label: String?, val running: Boolean) {
    val entry: HistoryEntry get() = row.entry
}

/**
 * One History row on two lines, with the thread row's anatomy ([TwoLineRow]). Line 1: the clock, or a green check for a
 * session marked done, the title (grey when done), a pin when pinned, and the age of the last activity on the right.
 * Line 2, small: the folder or worktree label in grey, then "running" in blue while a live claude holds the session. A
 * long title is cut, never the age, and then leads the tooltip, which otherwise is the session's folder. One row serves
 * every cell, and nothing here does I/O: the names are the marks already in memory.
 */
internal class HistoryRowRenderer(
    private val names: () -> Map<String, String> = { emptyMap() },
    private val now: () -> Long = System::currentTimeMillis,
) : ListCellRenderer<HistoryItem> {
    private val row = TwoLineRow().apply { status.text = RUNNING }

    /** The list font the small one was taken with; it is taken again only when that changes (theme, font size). */
    private var baseFont: Font? = null
    private var smallFont: Font? = null

    override fun getListCellRendererComponent(list: JList<out HistoryItem>, value: HistoryItem?, index: Int, selected: Boolean, cellHasFocus: Boolean): Component {
        val item = value ?: return row
        val base = list.font
        if (base !== baseFont) {
            baseFont = base
            smallFont = JBFont.small()
        }
        val focused = list.hasFocus()
        val foreground = if (selected) UIUtil.getListSelectionForeground(focused) else list.foreground
        val grey = if (selected && focused) foreground else NamedColorUtil.getInactiveTextColor()
        row.background = if (selected) UIUtil.getListSelectionBackground(focused) else list.background

        val entry = item.entry
        val done = item.row.done
        row.glyph.icon = if (done) AllIcons.General.GreenCheckmark else AllIcons.Vcs.History
        val shownTitle = DisplayNames.session(entry.sessionId, entry.title, names())
        row.title.text = shownTitle
        row.title.font = base
        row.title.foreground = if (done) grey else foreground
        row.pin.isVisible = item.row.pinned
        row.pin.icon = if (item.row.pinned) AllIcons.General.Pin_tab else null
        row.age.text = StatusText.age(entry.lastActivity, now())
        row.detail.text = item.label.orEmpty()
        for (label in row.greyLabels) {
            label.font = smallFont
            label.foreground = grey
        }
        row.status.isVisible = item.running
        row.status.font = smallFont
        // blue on the strong selection colour of a focused list would vanish
        row.status.foreground = if (selected && focused) foreground else SkeinIcons.YOUR_TURN_COLOR

        // the row gets the list's full width; a title cut there is still readable in the tooltip
        val cellWidth = list.width - list.insets.left - list.insets.right
        val titleCut = cellWidth > 0 && row.title.preferredSize.width > row.nameRoom(cellWidth)
        row.toolTipText = RowText.escapedTooltip(listOfNotNull(shownTitle.takeIf { titleCut }, entry.cwd))
        row.accessibleContext.accessibleName = listOfNotNull(shownTitle, DONE.takeIf { done }, item.label, RUNNING.takeIf { item.running }).joinToString(", ")
        row.invalidate() // the next validate lays the row out for this session, whatever the last one was
        return row
    }

    private companion object {
        const val RUNNING = "running"
        const val DONE = "done"
    }
}
