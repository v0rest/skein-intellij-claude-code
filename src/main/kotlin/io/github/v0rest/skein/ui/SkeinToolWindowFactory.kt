package io.github.v0rest.skein.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.actions.SkeinSettingsAction
import io.github.v0rest.skein.ui.actions.ViewOptionsGroup

/** The Skein tool window: right side by default, movable like any other. */
class SkeinToolWindowFactory : ToolWindowFactory, DumbAware {
    /**
     * Binds the needs-you badge when the tool window is registered: the content below is made only when the window is
     * first shown, and the dot matters most while it has not been opened yet.
     */
    override fun init(toolWindow: ToolWindow) {
        val service = ThreadService.getInstance(toolWindow.project)
        if (service.unsupported == null) ToolWindowBadge.bind(toolWindow, service.threads, toolWindow.disposable)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // the ⋮ menu leads to Skein's settings also where Skein is off: they belong to the IDE, not to this project
        toolWindow.setAdditionalGearActions(DefaultActionGroup(SkeinSettingsAction()))
        val unsupported = ThreadService.getInstance(project).unsupported
        val component = if (unsupported != null) {
            // Skein is off here: say why instead of showing an empty list
            JBPanelWithEmptyText().withEmptyText(unsupported.message)
        } else {
            ThreadListPanel(project, toolWindow.disposable).component.also {
                val actions = ActionManager.getInstance()
                // both are registered in plugin.xml; a missing one is a packaging bug, not something to hide
                val newThread = checkNotNull(actions.getAction(NEW_THREAD_ACTION_ID)) { "$NEW_THREAD_ACTION_ID is not registered" }
                val newWorktreeThread = checkNotNull(actions.getAction(NEW_WORKTREE_THREAD_ACTION_ID)) { "$NEW_WORKTREE_THREAD_ACTION_ID is not registered" }
                // two buttons side by side, "+" and the branch, so a worktree thread is one click away too
                toolWindow.setTitleActions(listOf(newThread, newWorktreeThread, ViewOptionsGroup()))
                // the new UI shows header buttons only while the tool window is active or hovered; "+" is how a thread
                // starts, so it stays in sight like the IDE's own "always show tool window header icons" setting does
                toolWindow.component.putClientProperty(ToolWindowContentUi.DONT_HIDE_TOOLBAR_IN_HEADER, true)
            }
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(component, null, false))
    }

    companion object {
        /** The id plugin.xml gives the tool window. */
        const val TOOL_WINDOW_ID = "Skein"
        const val NEW_THREAD_ACTION_ID = "Skein.NewThread"
        const val NEW_WORKTREE_THREAD_ACTION_ID = "Skein.NewWorktreeThread"
    }
}
