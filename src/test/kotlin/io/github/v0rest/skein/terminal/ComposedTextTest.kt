package io.github.v0rest.skein.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputMethodEvent
import javax.swing.JPanel

/**
 * An input method's text marks the thread tab whose view holds the event's source, the editor deep inside it, whichever
 * editor that is; nothing else is marked.
 */
class ComposedTextTest {
    private class Tab(val id: String, val view: JPanel?)

    @Test
    fun onlyTextCounts() {
        assertTrue(ComposedText.counts(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED))
        assertFalse(ComposedText.counts(InputMethodEvent.CARET_POSITION_CHANGED))
    }

    @Test
    fun theTabWhoseViewHoldsTheSourceIsTheOne() {
        val first = JPanel()
        val second = JPanel()
        // the output editor, and the alternate screen's swapped in deeper down
        val outputEditor = JPanel().also { first.add(it) }
        val alternateScreen = JPanel().also { inner -> JPanel().also { second.add(it) }.add(inner) }
        val tabs = listOf(Tab("t1", first), Tab("t2", second), Tab("t3", null))
        assertEquals("t1", ComposedText.holderOf(outputEditor, tabs) { it.view }?.id)
        assertEquals("t2", ComposedText.holderOf(alternateScreen, tabs) { it.view }?.id)
        assertEquals("the view itself", "t2", ComposedText.holderOf(second, tabs) { it.view }?.id)
    }

    @Test
    fun aSourceOutsideEveryTabMarksNone() {
        val tabs = listOf(Tab("t1", JPanel()), Tab("t2", null))
        assertNull(ComposedText.holderOf(JPanel(), tabs) { it.view })
        assertNull(ComposedText.holderOf(null, tabs) { it.view })
        assertNull(ComposedText.holderOf("not a component", tabs) { it.view })
        assertNull(ComposedText.holderOf(JPanel(), emptyList<Tab>()) { it.view })
    }
}
