package io.github.v0rest.skein.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.StatusBar
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.WaitingCounter

/** Opens the thread that has waited longest: a needs-you thread first, else a your-turn one. */
class JumpToWaitingThreadAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ThreadService.getInstance(project)
        val target = WaitingCounter.jumpTarget(service.threads.value)
        if (target != null) service.select(target) else StatusBar.Info.set("No Skein thread is waiting for you", project)
    }
}
