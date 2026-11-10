package io.github.v0rest.skein.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.OnePixelSplitter
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A section under the threads that opens and closes, as History and Worktrees do. While the section is open a
 * one-pixel divider sits between them, which the owner drags and the IDE remembers (the splitter keeps its proportion
 * under [proportionKey]); a closed section is only its header line at the bottom. A section that hides leaves the
 * threads the whole height, open or not. Whether the section was left open is kept in [memory], for the next start.
 */
class SectionSplit(
    private val threads: JComponent,
    private val section: JComponent,
    proportionKey: String,
    defaultProportion: Float,
    private val memory: OpenMemory,
) {
    /** Where the open or closed section is kept between IDE starts. */
    interface OpenMemory {
        var open: Boolean
    }

    /** The IDE's own settings, the same for all projects. */
    class IdeOpenMemory(private val key: String) : OpenMemory {
        override var open: Boolean
            get() = PropertiesComponent.getInstance().getBoolean(key, false)
            set(value) = PropertiesComponent.getInstance().setValue(key, value, false)
    }

    private val splitter = OnePixelSplitter(true, proportionKey, defaultProportion)

    val component: JComponent = JPanel(BorderLayout())

    /** Whether the section was open when the IDE last showed it. */
    val wasOpen: Boolean get() = memory.open

    init {
        // the threads never move: a component taken out of the hierarchy loses the keyboard focus, so only the section
        // goes between the splitter, which gives a lone first component the whole height, and the bottom edge
        splitter.firstComponent = threads
        component.add(splitter, BorderLayout.CENTER)
        layOut(open = false)
    }

    /** Lays the panel out for an open or closed section and remembers which one it is. */
    fun show(open: Boolean) {
        memory.open = open
        layOut(open)
    }

    private fun layOut(open: Boolean) {
        if (open) {
            component.remove(section)
            splitter.secondComponent = section
        } else {
            splitter.secondComponent = null
            component.add(section, BorderLayout.SOUTH)
        }
        component.revalidate()
        component.repaint()
    }
}
