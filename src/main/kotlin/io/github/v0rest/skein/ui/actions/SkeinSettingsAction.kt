package io.github.v0rest.skein.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import io.github.v0rest.skein.settings.SkeinConfigurable

/** "Skein Settings…" in the Skein tool window's ⋮ menu: opens Settings | Tools | Skein. */
class SkeinSettingsAction : DumbAwareAction("Skein Settings…") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, SkeinConfigurable::class.java)
    }
}
