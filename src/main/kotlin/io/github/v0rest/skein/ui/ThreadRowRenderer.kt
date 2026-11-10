package io.github.v0rest.skein.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.threads.ContextUse
import io.github.v0rest.skein.threads.DisplayNames
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadColor
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * One thread row on two lines. Line 1: the state glyph (the spinner while working), the name (bold while the thread
 * needs you or its finished turn is unread), a pin when pinned, and the age on the right ([showAge]). Line 2, grey and
 * small ([RowText.secondLine]): the worktree mark when there is one, then what the thread did last ([previews], by
 * thread id, while [showPreview] is on), or the state words without it. At the right end of line 2 the model family and
 * the context in use ([contexts], by thread id, while [showContext] is on and the value is known, nothing otherwise),
 * which keeps its whole text so the preview gives way first; the tooltip has the full model id and token count
 * ([RowText.tooltip], one thing on each line, no preview and no path). A coloured thread has a full-height stripe at
 * the left edge, kept on a selected row, and its tint as the background when not selected; the thread whose tab is in
 * front ([inFront]) has a thin marker in the selection colour while it is not selected. A worktree thread shows its
 * worktree's counts ([worktreeCounts], by thread id, [WorktreeBadge]) on line 1 right before the age, `✎N` in amber,
 * and their lines at the end of the tooltip. A long name is cut, never the counts or the age, and then leads the
 * tooltip; line 2 is cut at the row's end. One [TwoLineRow] serves every cell, and nothing here does I/O: the
 * previews and the counts are read elsewhere.
 */
class ThreadRowRenderer(
    private val names: () -> Map<String, String> = { emptyMap() },
    private val inFront: () -> String? = { null },
    private val showAge: () -> Boolean = { true },
    private val previews: () -> Map<String, String> = { emptyMap() },
    private val showPreview: () -> Boolean = { true },
    private val contexts: () -> Map<String, ContextUse> = { emptyMap() },
    private val showContext: () -> Boolean = { true },
    private val worktreeCounts: () -> Map<String, WorktreeCounts> = { emptyMap() },
    private val now: () -> Long = System::currentTimeMillis,
) : ListCellRenderer<SkeinThread> {
    private val row = TwoLineRow()
    private val tints: Map<ThreadColor, Color> = ThreadColor.entries.associateWith(ThreadColors::rowTint)
    private val marks: Map<ThreadColor, Color> = ThreadColor.entries.associateWith(ThreadColors::mark)

    /** The list font the others were derived from; they are derived again only when it changes (theme, font size). */
    private var baseFont: Font? = null
    private var boldFont: Font? = null
    private var smallFont: Font? = null

    override fun getListCellRendererComponent(list: JList<out SkeinThread>, value: SkeinThread?, index: Int, selected: Boolean, cellHasFocus: Boolean): Component {
        val thread = value ?: return row
        val base = list.font
        if (base !== baseFont) {
            baseFont = base
            boldFont = base.deriveFont(Font.BOLD)
            smallFont = JBFont.small()
        }
        val focused = list.hasFocus()
        val foreground = if (selected) UIUtil.getListSelectionForeground(focused) else list.foreground
        val grey = if (selected && focused) foreground else NamedColorUtil.getInactiveTextColor()
        val color = thread.color
        row.background = when {
            selected -> UIUtil.getListSelectionBackground(focused)
            color != null -> tints.getValue(color)
            else -> list.background
        }
        row.stripe = color?.let(marks::getValue)
        row.frontMarker = !selected && thread.id == inFront()

        row.glyph.icon = SkeinIcons.forState(thread.state)
        val shownName = DisplayNames.thread(thread, names())
        row.title.text = shownName
        row.title.font = if (RowText.bold(thread.state)) boldFont else base
        row.title.foreground = foreground
        row.pin.isVisible = thread.pinned
        row.pin.icon = if (thread.pinned) AllIcons.General.Pin_tab else null
        row.age.isVisible = showAge()
        row.age.text = if (row.age.isVisible) StatusText.age(thread.stateSince, now()) else ""
        val worktree = WorktreeLabel.of(shownName, thread.worktree, thread.sessionCwd)
        val preview = if (showPreview()) previews()[thread.id] else null
        row.detail.text = RowText.secondLine(thread.state, worktree, preview)
        val use = if (showContext()) contexts()[thread.id] else null
        row.context.isVisible = use != null
        row.context.text = use?.label.orEmpty()
        for (label in row.greyLabels) {
            label.font = smallFont
            label.foreground = grey
        }
        val counts = if (thread.worktree != null) worktreeCounts()[thread.id] else null
        showBadge(row, WorktreeBadge.of(counts), smallFont, grey, if (selected && focused) foreground else null)
        // the row gets the list's full width; a name cut there is still readable in the tooltip
        val cellWidth = list.width - list.insets.left - list.insets.right
        val nameCut = cellWidth > 0 && row.title.preferredSize.width > row.nameRoom(cellWidth)
        row.toolTipText = RowText.tooltip(
            thread,
            cutName = if (nameCut) shownName else null,
            context = use,
            now = now(),
            lines = WorktreeBadge.tooltip(counts),
        )
        // a screen reader hears the name and the state words, whatever line 2 shows, then the worktree and the preview
        row.accessibleContext.accessibleName = listOfNotNull(shownName, StatusText.line(thread.state), worktree, WorktreeBadge.spoken(counts), preview, use?.spoken).joinToString(", ")
        row.invalidate() // the next validate lays the row out for this thread, whatever the last one was
        return row
    }

    companion object {
        /** Amber: uncommitted work in a worktree, or an operation stopped half way there. */
        val WORKTREE_CHANGES_COLOR = JBColor(Color(0x9A6B00), Color(0xDDB54A))

        /**
         * Shows [badge] on line 1 of [row] (nothing for null): the changes in amber or [grey] as it asks, the commits ahead
         * in [grey], both in [font]; [selectedForeground], when given, for both, since amber on a strong selection would
         * vanish.
         */
        internal fun showBadge(row: TwoLineRow, badge: Badge?, font: Font?, grey: Color, selectedForeground: Color?) {
            row.changes.isVisible = badge?.changes != null
            row.changes.text = badge?.changes.orEmpty()
            row.ahead.isVisible = badge?.ahead != null
            row.ahead.text = badge?.ahead.orEmpty()
            row.changes.font = font
            row.ahead.font = font
            row.changes.foreground = selectedForeground ?: if (badge?.look == Badge.Look.AMBER) WORKTREE_CHANGES_COLOR else grey
            row.ahead.foreground = selectedForeground ?: grey
        }
    }
}
