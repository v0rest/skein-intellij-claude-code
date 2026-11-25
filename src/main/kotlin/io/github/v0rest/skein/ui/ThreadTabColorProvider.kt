package io.github.v0rest.skein.ui

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.IslandsState
import io.github.v0rest.skein.terminal.TerminalAdapter
import io.github.v0rest.skein.threads.ThreadService
import java.awt.Color

/**
 * Tints the editor tab of a coloured thread. The platform calls it from a background read action for every tab, so it
 * stays cheap: no I/O, no Swing, no service creation. DumbAware, because it needs no indices and the platform skips
 * other providers while the project is indexing, which would leave restored tabs untinted.
 *
 * Under the New UI's Islands themes it hands the tab its own colour ([ThreadColors.tab]), stronger than the row's
 * tint, since that painter shows an unselected tab, and a selected one without the focus, at 40% of it, and the row's
 * tint would barely show there; other painters get the row's tint. Whether the Islands painter is on is what the IDE
 * itself decided for the current theme (`IslandsState.isEnabled`, public, a volatile read - javap, 263.7307.200 and
 * 263.8311.30). After a theme switch the IDE asks here before it has settled that, so ThreadService redraws the coloured
 * tabs once more when the theme changes.
 */
class ThreadTabColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? {
        val id = TerminalAdapter.threadIdOf(file) ?: return null
        val service = project.serviceIfCreated<ThreadService>() ?: return null
        val color = service.threads.value.firstOrNull { it.id == id }?.color ?: return null
        return ThreadColors.tab(color, islandsPainter())
    }

    /** Whether the Islands painter draws the tabs; false, the row's tint, in an IDE that no longer has this class. */
    private fun islandsPainter(): Boolean = try {
        IslandsState.isEnabled()
    } catch (_: LinkageError) {
        false
    }
}
