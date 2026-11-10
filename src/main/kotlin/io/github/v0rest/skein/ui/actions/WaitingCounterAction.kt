package io.github.v0rest.skein.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareAction
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.SkeinIcons
import io.github.v0rest.skein.ui.WaitingCounter

/** Main-toolbar widget: how many threads of this project need you; a click opens the one that waited longest. */
class WaitingCounterAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        val project = e.project
        // serviceIfCreated: this runs constantly in the toolbar and must not start Skein in projects that never opened it
        val service = project?.serviceIfCreated<ThreadService>()
        if (project == null || service == null || !SkeinSettings.getInstance().state.showWaitingCounter) {
            presentation.isEnabledAndVisible = false
            return
        }
        val names = project.serviceIfCreated<SessionMarksService>()?.marks?.value?.names.orEmpty()
        val waiting = WaitingCounter.of(service.threads.value, names)
        if (waiting.count == 0) {
            presentation.isEnabledAndVisible = false
            return
        }
        presentation.isEnabledAndVisible = true
        presentation.icon = SkeinIcons.NEEDS_YOU
        // the toolbar shows the bare count next to the dot; Find Action and the overflow menu need the action's name
        presentation.text = if (e.isFromActionToolbar) waiting.count.toString() else "${templateText ?: TEMPLATE_TEXT} (${waiting.count})"
        presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        presentation.description = "Needs you: " + waiting.names.joinToString(", ")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.serviceIfCreated<ThreadService>() ?: return
        val names = project.serviceIfCreated<SessionMarksService>()?.marks?.value?.names.orEmpty()
        WaitingCounter.of(service.threads.value, names).longestWaitingId?.let { service.select(it) }
    }

    private companion object {
        /** The action's name as plugin.xml registers it, for a presentation without a template text. */
        const val TEMPLATE_TEXT = "Threads That Need You"
    }
}
