package io.github.v0rest.skein.terminal

import java.awt.Component
import java.awt.event.InputMethodEvent
import javax.swing.SwingUtilities

/**
 * Which thread tab an input method typed into ([DraftGuard]): its text (a CJK input method, an accent picked from a
 * menu) comes as input method events on whichever editor the terminal shows, the alternate screen's included, so the tab
 * is the one whose view component holds the event's source. Called for every input method event in the IDE, so it does
 * nothing but that look. EDT only.
 */
object ComposedText {
    /** Whether an input method event of this [id] brings text; a caret that moved brings none. */
    fun counts(id: Int): Boolean = id == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED

    /** The one of [holders] whose component ([componentOf], null for none yet) holds [source]; null when none does. */
    fun <T> holderOf(source: Any?, holders: Collection<T>, componentOf: (T) -> Component?): T? {
        val component = source as? Component ?: return null
        return holders.firstOrNull { holder -> componentOf(holder)?.let { SwingUtilities.isDescendingFrom(component, it) } == true }
    }
}
