package io.github.v0rest.skein.ui

import com.intellij.ui.OnePixelSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import javax.swing.JPanel

class SectionSplitTest {
    private val threads = JPanel()
    private val section = JPanel()
    private val memory = object : SectionSplit.OpenMemory {
        override var open = false
    }

    private fun split() = SectionSplit(threads, section, "Skein.Test.Proportion", 0.7f, memory)
    private fun SectionSplit.at(position: String) = (component.layout as BorderLayout).getLayoutComponent(position)
    private fun SectionSplit.splitter() = at(BorderLayout.CENTER) as OnePixelSplitter

    @Test
    fun aClosedSectionIsOnlyItsHeaderUnderTheThreads() {
        val split = split()
        assertSame(threads, split.splitter().firstComponent)
        assertNull(split.splitter().secondComponent)
        assertSame(section, split.at(BorderLayout.SOUTH))
    }

    @Test
    fun anOpenSectionSitsUnderADividerWithItsOwnRememberedProportion() {
        val split = split()
        split.show(open = true)
        val splitter = split.splitter()
        assertTrue(splitter.isVertical)
        assertSame(section, splitter.secondComponent)
        assertNull(split.at(BorderLayout.SOUTH))
        assertEquals("Skein.Test.Proportion", splitter.splitterProportionKey)
    }

    @Test
    fun closingItAgainTakesTheDividerAway() {
        val split = split()
        split.show(open = true)
        split.show(open = false)
        assertNull(split.splitter().secondComponent)
        assertSame(section, split.at(BorderLayout.SOUTH))
    }

    @Test
    fun anOpenSectionThatHidesLeavesTheThreadsTheWholeHeightWithNoDivider() {
        // the Worktrees section hides while no worktree is listed, open or not
        val split = split()
        split.show(open = true)
        section.isVisible = false
        val splitter = split.splitter()
        splitter.setSize(200, 300)
        splitter.doLayout()
        assertEquals(300, threads.height)
        assertFalse(splitter.divider.isVisible)
    }

    @Test
    fun theThreadsNeverMoveSoTheyKeepTheKeyboardFocus() {
        val split = split()
        val parent = threads.parent
        split.show(open = true)
        assertSame(parent, threads.parent)
        split.show(open = false)
        assertSame(parent, threads.parent)
    }

    @Test
    fun remembersWhetherTheSectionWasLeftOpen() {
        val split = split()
        assertFalse(split.wasOpen)
        split.show(open = true)
        assertTrue(memory.open)
        assertTrue(SectionSplit(JPanel(), JPanel(), "Skein.Test.Proportion", 0.7f, memory).wasOpen)
        split.show(open = false)
        assertFalse(memory.open)
    }

    @Test
    fun buildingTheLayoutKeepsAnOpenSectionRemembered() {
        memory.open = true
        split()
        assertTrue(memory.open)
    }

    @Test
    fun worktreesAndHistoryKeepTheirOwnSettings() {
        val keys = listOf(
            ThreadListPanel.WORKTREES_PROPORTION_KEY,
            ThreadListPanel.WORKTREES_OPEN_KEY,
            HistorySplit.PROPORTION_KEY,
            HistorySplit.OPEN_KEY,
        )
        assertEquals(keys.size, keys.toSet().size)
    }
}
