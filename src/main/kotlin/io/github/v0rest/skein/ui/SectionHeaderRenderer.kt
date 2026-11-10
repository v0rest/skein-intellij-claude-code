package io.github.v0rest.skein.ui

import com.intellij.ui.TitledSeparator
import io.github.v0rest.skein.threads.SkeinThread
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** Draws [row] and, above the rows listed in [headers] (thread id to section title), a titled separator. */
class SectionHeaderRenderer(
    private val row: ListCellRenderer<SkeinThread>,
    private val headers: () -> Map<String, String>,
) : ListCellRenderer<SkeinThread> {
    private val separator = TitledSeparator()
    private val panel = JPanel(BorderLayout()).apply { isOpaque = false }

    override fun getListCellRendererComponent(list: JList<out SkeinThread>, value: SkeinThread?, index: Int, selected: Boolean, hasFocus: Boolean): Component {
        val component = row.getListCellRendererComponent(list, value, index, selected, hasFocus)
        val title = value?.let { headers()[it.id] } ?: return component
        panel.removeAll()
        separator.text = title
        panel.add(separator, BorderLayout.NORTH)
        panel.add(component, BorderLayout.CENTER)
        panel.toolTipText = (component as? javax.swing.JComponent)?.toolTipText
        return panel
    }
}
