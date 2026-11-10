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

class HistorySplitTest {
    private val threads = JPanel()
    private val history = JPanel()
    private val memory = object : HistorySplit.Memory {
        override var historyOpen = false
    }

    private fun HistorySplit.at(position: String) = (component.layout as BorderLayout).getLayoutComponent(position)
    private fun HistorySplit.splitter() = at(BorderLayout.CENTER) as OnePixelSplitter

    @Test
    fun aClosedHistoryIsOnlyItsHeaderUnderTheThreads() {
        val split = HistorySplit(threads, history, memory)
        assertSame(threads, split.splitter().firstComponent)
        assertNull(split.splitter().secondComponent)
        assertSame(history, split.at(BorderLayout.SOUTH))
    }

    @Test
    fun aClosedHistoryLeavesTheThreadsTheWholeHeightWithNoDivider() {
        val splitter = HistorySplit(threads, history, memory).splitter()
        splitter.setSize(200, 300)
        splitter.doLayout()
        assertEquals(300, threads.height)
        assertFalse(splitter.divider.isVisible)
    }

    @Test
    fun anOpenHistorySitsUnderADividerTheIdeRemembers() {
        val split = HistorySplit(threads, history, memory)
        split.show(open = true)
        val splitter = split.splitter()
        assertTrue(splitter.isVertical)
        assertSame(threads, splitter.firstComponent)
        assertSame(history, splitter.secondComponent)
        assertNull(split.at(BorderLayout.SOUTH))
        assertEquals(HistorySplit.PROPORTION_KEY, splitter.splitterProportionKey)
    }

    @Test
    fun closingTheHistoryAgainTakesTheDividerAway() {
        val split = HistorySplit(threads, history, memory)
        split.show(open = true)
        split.show(open = false)
        assertNull(split.splitter().secondComponent)
        assertSame(history, split.at(BorderLayout.SOUTH))
    }

    @Test
    fun theThreadsNeverMoveSoTheyKeepTheKeyboardFocus() {
        // a component taken out of the hierarchy loses the focus, so only the history may move
        val split = HistorySplit(threads, history, memory)
        val parent = threads.parent
        split.show(open = true)
        assertSame(parent, threads.parent)
        split.show(open = false)
        assertSame(parent, threads.parent)
    }

    @Test
    fun remembersWhetherTheHistoryWasLeftOpen() {
        val split = HistorySplit(threads, history, memory)
        assertFalse(split.wasOpen)
        split.show(open = true)
        assertTrue(memory.historyOpen)
        assertTrue(HistorySplit(JPanel(), JPanel(), memory).wasOpen)
        split.show(open = false)
        assertFalse(memory.historyOpen)
    }

    @Test
    fun buildingTheLayoutKeepsAnOpenHistoryRemembered() {
        memory.historyOpen = true
        HistorySplit(threads, history, memory)
        assertTrue(memory.historyOpen)
    }
}
