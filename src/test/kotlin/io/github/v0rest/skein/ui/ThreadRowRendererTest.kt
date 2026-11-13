package io.github.v0rest.skein.ui

import io.github.v0rest.skein.git.Changes
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.threads.ContextUse
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadColor
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JList

/** The thread row laid out and painted headless, the way the list draws it. */
class ThreadRowRendererTest {
    private val list = JList<SkeinThread>()
    private val now = 1_000_000_000L

    private fun thread(name: String = "acme", color: ThreadColor? = null, worktree: String? = null, sessionCwd: String? = null) =
        SkeinThread("t1", "/w/acme", null, name, ThreadState.Idle, now - 3 * 60_000, 0, null, color = color, sessionCwd = sessionCwd, worktree = worktree)

    private fun render(
        thread: SkeinThread,
        selected: Boolean = false,
        showAge: Boolean = true,
        inFront: String? = null,
        width: Int = 220,
        previews: Map<String, String> = emptyMap(),
        showPreview: Boolean = true,
        contexts: Map<String, ContextUse> = emptyMap(),
        showContext: Boolean = true,
        worktreeCounts: Map<String, WorktreeCounts> = emptyMap(),
    ): TwoLineRow {
        // the list gives every row its own width, as it does in the tool window
        list.setSize(width, 400)
        val renderer = ThreadRowRenderer(inFront = { inFront }, showAge = { showAge }, previews = { previews }, showPreview = { showPreview }, contexts = { contexts }, showContext = { showContext }, worktreeCounts = { worktreeCounts }, now = { now })
        val row = renderer.getListCellRendererComponent(list, thread, 0, selected, false) as TwoLineRow
        row.setSize(width, row.preferredSize.height)
        row.doLayout()
        return row
    }

    private fun TwoLineRow.paintedAt(x: Int, y: Int): Int {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            paint(g)
        } finally {
            g.dispose()
        }
        return image.getRGB(x, y) and 0xFFFFFF
    }

    @Test
    fun aLongNameIsCutAndTheAgeStaysVisible() {
        val row = render(thread(name = "a".repeat(120)))
        assertTrue(row.age.isVisible)
        assertEquals("3 min", row.age.text)
        assertTrue("the age is not squeezed", row.age.width >= row.age.preferredSize.width)
        assertTrue("the age lies inside the row: ${row.age.bounds}", row.age.x >= 0 && row.age.x + row.age.width <= 220)
        assertTrue("the name is cut", row.title.width < row.title.preferredSize.width)
        assertTrue("the name stops before the age", row.title.x + row.title.width <= row.age.x)
        assertTrue("the row asks for no more than the list gives it", row.preferredSize.width <= 220)
    }

    @Test
    fun theAgeIsHiddenWhenSwitchedOff() {
        val long = thread(name = "a".repeat(120))
        val withAge = render(long)
        val withoutAge = render(long, showAge = false)
        assertFalse(withoutAge.age.isVisible)
        assertTrue("the name takes the room the age had", withoutAge.title.width > withAge.title.width)
    }

    @Test
    fun aColouredRowHasItsStripeWhenSelected() {
        val mark = ThreadColors.mark(ThreadColor.BLUE)
        for (selected in listOf(false, true)) {
            val row = render(thread(color = ThreadColor.BLUE), selected = selected)
            assertEquals("selected=$selected", mark, row.stripe)
            assertEquals("painted, selected=$selected", mark.rgb and 0xFFFFFF, row.paintedAt(1, row.height / 2))
        }
        assertEquals(ThreadColors.rowTint(ThreadColor.BLUE), render(thread(color = ThreadColor.BLUE)).background)
        assertNull(render(thread()).stripe)
    }

    @Test
    fun theThreadInFrontHasItsMarkerUnlessSelected() {
        assertTrue(render(thread(), inFront = "t1").frontMarker)
        assertFalse(render(thread(), inFront = "t1", selected = true).frontMarker)
        assertFalse(render(thread(), inFront = "t2").frontMarker)
        assertFalse(render(thread(), inFront = null).frontMarker)
    }

    @Test
    fun theSecondLineSaysTheStateAndTheTooltipTheFolder() {
        val row = render(thread())
        assertEquals("without a preview, the state words", "idle", row.detail.text)
        assertEquals("<html>idle", row.toolTipText)
        assertTrue("line 2 starts under the name", row.detail.x == row.title.x && row.detail.y >= row.title.y + row.title.height)
        assertEquals("no path, a worktree's neither", "<html>idle", render(thread(sessionCwd = "/w/acme/.claude/worktrees/feat-x")).toolTipText)
    }

    @Test
    fun theSecondLineShowsWhatHappenedLast() {
        val previews = mapOf("t1" to "Tests are green.", "t2" to "Bash: ./gradlew test")
        val row = render(thread(), previews = previews)
        assertEquals("Tests are green.", row.detail.text)
        assertEquals("the state stays in the tooltip", "<html>idle", row.toolTipText)
        assertEquals("switched off: the state words", "idle", render(thread(), previews = previews, showPreview = false).detail.text)
    }

    @Test
    fun theWorktreeMarkLeadsTheSecondLine() {
        val worktree = thread(name = "Fix login", worktree = "feat-x")
        val preview = mapOf("t1" to "Tests are green.")
        val row = render(worktree, previews = preview)
        assertEquals("worktree feat-x · Tests are green.", row.detail.text)
        assertEquals("worktree feat-x · idle", render(worktree).detail.text)
        assertEquals("worktree feat-x · idle", render(worktree, previews = preview, showPreview = false).detail.text)
        val firstLine = row.components.filterIsInstance<JLabel>().filter { it !== row.detail && it.isVisible }
        assertTrue("line 1 has no worktree mark", firstLine.none { it.text.orEmpty().contains("worktree") })
    }

    @Test
    fun aLongPreviewIsCutAtTheRowsEnd() {
        val row = render(thread(), previews = mapOf("t1" to "x".repeat(120)))
        assertTrue("the preview is cut", row.detail.width < row.detail.preferredSize.width)
        assertTrue("the preview stays inside the row: ${row.detail.bounds}", row.detail.x + row.detail.width <= 220)
        assertTrue("the row asks for no more than the list gives it", row.preferredSize.width <= 220)
    }

    @Test
    fun theTooltipNeverRepeatsThePreview() {
        // the preview is claude's own words: line 2 shows what fits, the tab has the rest
        val long = "Bash: ./gradlew test " + "x".repeat(120)
        assertEquals("<html>idle", render(thread(), previews = mapOf("t1" to long)).toolTipText)
        val crowded = render(thread(name = "Fix login", worktree = "w".repeat(120)), previews = mapOf("t1" to "ok"))
        assertEquals("<html>idle", crowded.toolTipText)
        val markup = """<img src="https://example.test/x.png">""" + "x".repeat(120)
        assertEquals("<html>idle", render(thread(), previews = mapOf("t1" to markup)).toolTipText)
    }

    @Test
    fun aCutNameLeadsTheTooltip() {
        val long = "a".repeat(120)
        assertEquals("<html>$long<br>idle", render(thread(name = long)).toolTipText)
        assertEquals("a name that fits is not repeated", "<html>idle", render(thread(name = "acme")).toolTipText)
    }

    @Test
    fun aNameIsNeverReadAsHtml() {
        val row = render(thread(name = "<html><b>acme</b>"))
        assertEquals(true, row.title.getClientProperty("html.disable"))
        assertNull(row.title.getClientProperty("html"))
        // a name the row cuts leads the tooltip, which Swing reads as HTML when it starts with <html>
        val cut = render(thread(name = """<html><img src="https://example.test/x.png">""" + "a".repeat(120)))
        assertTrue("the name is cut", cut.title.width < cut.title.preferredSize.width)
        val tooltip = cut.toolTipText
        assertTrue(tooltip, tooltip.startsWith("<html>&lt;html&gt;&lt;img src=&quot;https://example.test/x.png&quot;&gt;aaa"))
        assertFalse("no markup but the line breaks: $tooltip", tooltip.removePrefix("<html>").replace("<br>", "").contains('<'))
    }

    private val opus = mapOf("t1" to ContextUse("claude-opus-5-5", 385_279, now - 2 * 60_000))

    @Test
    fun theSecondLineShowsTheModelAndContextOnTheRight() {
        val row = render(thread(), previews = mapOf("t1" to "Tests are green."), contexts = opus)
        assertTrue(row.context.isVisible)
        assertEquals("Opus · 385k", row.context.text)
        assertEquals("Tests are green.", row.detail.text)
        assertEquals("<html>idle · 2 min ago<br>claude-opus-5-5 · 385,279 tokens in context", row.toolTipText)
        assertEquals("the part ends where the age does", row.age.x + row.age.width, row.context.x + row.context.width)
        assertTrue("on line 2", row.context.y >= row.title.y + row.title.height && row.context.y == row.detail.y)
        assertTrue("the preview stops before it", row.detail.x + row.detail.width <= row.context.x)
        assertTrue("never squeezed", row.context.width >= row.context.preferredSize.width)
        assertTrue(row.accessibleContext.accessibleName.endsWith("Opus, 385k tokens of context"))
    }

    @Test
    fun nothingShowsWithoutAKnownValueOrWithTheSettingOff() {
        val unknown = render(thread(), contexts = emptyMap())
        assertFalse(unknown.context.isVisible)
        assertEquals("", unknown.context.text)
        assertEquals("<html>idle", unknown.toolTipText)
        val off = render(thread(), contexts = opus, showContext = false)
        assertFalse(off.context.isVisible)
        assertEquals("<html>idle", off.toolTipText)
        assertEquals("idle", off.detail.text)
    }

    @Test
    fun aLongPreviewGivesWayToTheContextAtANarrowWidth() {
        val row = render(thread(), previews = mapOf("t1" to "x".repeat(120)), contexts = opus, width = 160)
        assertTrue("the preview is cut", row.detail.width < row.detail.preferredSize.width)
        assertEquals("the context keeps its whole text", row.context.preferredSize.width, row.context.width)
        assertTrue(row.context.x + row.context.width <= 160)
        assertTrue(row.detail.x + row.detail.width <= row.context.x)
        assertFalse("the tooltip leaves the preview out", row.toolTipText.contains("x".repeat(20)))
    }

    private val dirty = mapOf("t1" to WorktreeCounts.Counted(Changes.of(listOf("a.kt", "b <c>.kt", "d.kt")), detached = false, base = "main", ahead = 2))

    @Test
    fun aWorktreeRowShowsItsCountsRightBeforeTheAge() {
        val row = render(thread(name = "a".repeat(120), worktree = "acme"), worktreeCounts = dirty)
        assertTrue(row.changes.isVisible && row.ahead.isVisible)
        assertEquals("✎3", row.changes.text)
        assertEquals("↑2", row.ahead.text)
        assertEquals(ThreadRowRenderer.WORKTREE_CHANGES_COLOR, row.changes.foreground)
        assertTrue("the counts keep their width", row.changes.width >= row.changes.preferredSize.width && row.ahead.width >= row.ahead.preferredSize.width)
        assertTrue("✎ before ↑: ${row.changes.bounds} ${row.ahead.bounds}", row.changes.x + row.changes.width <= row.ahead.x)
        assertTrue("↑ before the age: ${row.ahead.bounds} ${row.age.bounds}", row.ahead.x + row.ahead.width <= row.age.x)
        assertTrue("the name stops before the counts", row.title.x + row.title.width <= row.changes.x)
        assertTrue("the age lies inside the row", row.age.x + row.age.width <= 220)
    }

    @Test
    fun theCountsTakeTheAgesPlaceWhenItIsOff() {
        val row = render(thread(worktree = "acme"), worktreeCounts = dirty, showAge = false)
        assertTrue(row.ahead.x + row.ahead.width <= 220 && row.ahead.x + row.ahead.width >= 220 - 16)
    }

    @Test
    fun theTooltipListsTheChangedPathsEscaped() {
        val tip = render(thread(worktree = "acme"), worktreeCounts = dirty).toolTipText
        assertTrue(tip, tip.endsWith("<br>1. a.kt<br>2. b &lt;c&gt;.kt<br>3. d.kt<br>2 commits not in main"))
    }

    @Test
    fun aThreadOutsideAWorktreeShowsNoCounts() {
        val row = render(thread(), worktreeCounts = dirty)
        assertFalse(row.changes.isVisible)
        assertFalse(row.ahead.isVisible)
        assertFalse(row.toolTipText.contains("<br>"))
    }

    @Test
    fun aFailedLookIsAGreyQuestionMarkAndACleanOneShowsNothing() {
        val failed = render(thread(worktree = "acme"), worktreeCounts = mapOf("t1" to WorktreeCounts.Failed("git did not finish in time")))
        assertEquals("?", failed.changes.text)
        assertFalse(failed.ahead.isVisible)
        assertTrue(failed.changes.foreground != ThreadRowRenderer.WORKTREE_CHANGES_COLOR)
        assertTrue(failed.toolTipText.endsWith("<br>git could not read the worktree: git did not finish in time"))
        val clean = render(thread(worktree = "acme"), worktreeCounts = mapOf("t1" to WorktreeCounts.Counted(Changes.NONE, false, "main", 0)))
        assertFalse(clean.changes.isVisible || clean.ahead.isVisible)
        assertTrue(clean.toolTipText.endsWith("<br>Clean, level with main"))
    }
}
