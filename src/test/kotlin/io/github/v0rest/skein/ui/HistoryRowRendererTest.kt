package io.github.v0rest.skein.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.NamedColorUtil
import io.github.v0rest.skein.claude.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JList

/** The History row laid out headless, the way the list draws it. */
class HistoryRowRendererTest {
    private val list = JList<HistoryItem>()
    private val now = 1_000_000_000L
    private val id = "55555555-5555-4555-8555-555555555555"

    private fun item(title: String = "Fix login", done: Boolean = false, pinned: Boolean = false, label: String? = null, running: Boolean = false) =
        HistoryItem(HistoryRow(HistoryEntry(id, "/w/acme", title, now - 3 * 60_000, null), pinned = pinned, done = done), label, running)

    private fun render(item: HistoryItem, selected: Boolean = false, width: Int = 220): TwoLineRow {
        // the list gives every row its own width, as it does in the tool window
        list.setSize(width, 400)
        val row = HistoryRowRenderer(now = { now }).getListCellRendererComponent(list, item, 0, selected, false) as TwoLineRow
        row.setSize(width, row.preferredSize.height)
        row.doLayout()
        return row
    }

    @Test
    fun aDoneSessionShowsTheCheckAndAGreyTitle() {
        val done = render(item(done = true))
        assertSame(AllIcons.General.GreenCheckmark, done.glyph.icon)
        assertEquals(NamedColorUtil.getInactiveTextColor(), done.title.foreground)
        val open = render(item())
        assertSame(AllIcons.Vcs.History, open.glyph.icon)
        assertEquals(list.foreground, open.title.foreground)
    }

    @Test
    fun aRunningSessionSaysRunningOnItsSecondLine() {
        val row = render(item(label = "worktree feat-x", running = true))
        assertEquals("worktree feat-x", row.detail.text)
        assertTrue(row.status.isVisible)
        assertEquals("running", row.status.text)
        assertEquals(SkeinIcons.YOUR_TURN_COLOR, row.status.foreground)
        assertTrue("running is on line 2", row.status.y >= row.title.y + row.title.height)
        assertTrue("running follows the label", row.status.x >= row.detail.x + row.detail.width)
        assertTrue("running lies inside the row: ${row.status.bounds}", row.status.x + row.status.width <= 220)
        assertFalse("a session nobody runs says nothing of it", render(item(label = "worktree feat-x")).status.isVisible)
        val inBase = render(item(running = true))
        assertEquals("without a label, running leads line 2", inBase.title.x, inBase.status.x)
        assertEquals("a row without a label keeps its second line", render(item(label = "docs")).preferredSize.height, render(item()).preferredSize.height)
    }

    @Test
    fun theAgeStaysVisibleForALongTitle() {
        val row = render(item(title = "a".repeat(120), pinned = true, label = "worktree feat-x", running = true))
        assertEquals("3 min", row.age.text)
        assertTrue("the age is not squeezed", row.age.width >= row.age.preferredSize.width)
        assertTrue("the age lies inside the row: ${row.age.bounds}", row.age.x >= 0 && row.age.x + row.age.width <= 220)
        assertTrue("the title is cut", row.title.width < row.title.preferredSize.width)
        assertTrue("the title stops before the age", row.title.x + row.title.width <= row.age.x)
        assertTrue("the row asks for no more than the list gives it", row.preferredSize.width <= 220)
    }

    @Test
    fun theTooltipIsTheFolderAndNeverHtml() {
        assertEquals("<html>/w/acme", render(item()).toolTipText)
        val title = """<html><img src="https://example.test/x.png">""" + "a".repeat(120)
        val cut = render(item(title = title))
        assertEquals(true, cut.title.getClientProperty("html.disable"))
        assertNull(cut.title.getClientProperty("html"))
        val tooltip = cut.toolTipText
        assertTrue("a cut title leads the tooltip: $tooltip", tooltip.startsWith("<html>&lt;html&gt;&lt;img src=&quot;https://example.test/x.png&quot;&gt;aaa"))
        assertTrue(tooltip, tooltip.endsWith(" - /w/acme"))
        assertFalse("no markup after the prefix: $tooltip", tooltip.removePrefix("<html>").contains('<'))
    }
}
