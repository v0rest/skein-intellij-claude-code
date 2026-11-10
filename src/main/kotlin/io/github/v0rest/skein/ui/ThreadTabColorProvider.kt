package io.github.v0rest.skein.ui

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.v0rest.skein.terminal.TerminalAdapter
import io.github.v0rest.skein.threads.ThreadService
import java.awt.Color

/**
 * Tints the editor tab of a coloured thread. The platform calls it from a background read action for every tab, so it
 * stays cheap: no I/O, no Swing, no service creation. DumbAware, because it needs no indices and the platform skips
 * other providers while the project is indexing, which would leave restored tabs untinted.
 */
class ThreadTabColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? {
        val id = TerminalAdapter.threadIdOf(file) ?: return null
        val service = project.serviceIfCreated<ThreadService>() ?: return null
        val color = service.threads.value.firstOrNull { it.id == id }?.color ?: return null
        return ThreadColors.of(color)
    }
}
