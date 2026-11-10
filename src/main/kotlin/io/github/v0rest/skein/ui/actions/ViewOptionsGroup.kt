package io.github.v0rest.skein.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareToggleAction
import io.github.v0rest.skein.settings.SkeinSettings

/** The settings the View Options menu switches, in its order; each reads and writes one field of the state it is given. */
object ViewOptions {
    class Field(val text: String, val get: (SkeinSettings.State) -> Boolean, val set: (SkeinSettings.State, Boolean) -> Unit)

    val fields: List<Field> = listOf(
        Field("Only Threads Waiting for Me", { it.onlyWaitingThreads }, { s, v -> s.onlyWaitingThreads = v }),
        Field("Group by State", { it.groupThreadsByState }, { s, v -> s.groupThreadsByState = v }),
        Field("Show Last Message", { it.showPreview }, { s, v -> s.showPreview = v }),
        Field("Show Time", { it.showAge }, { s, v -> s.showAge = v }),
        Field("Show Model and Context", { it.showContext }, { s, v -> s.showContext = v }),
    )
}

/** The eye in the title bar: one menu for which threads the list shows, how it groups them and what a row shows. */
class ViewOptionsGroup : DefaultActionGroup("View Options", true), DumbAware {
    init {
        templatePresentation.icon = AllIcons.Actions.Show
        ViewOptions.fields.forEachIndexed { index, field ->
            // the list's own options, then the row's
            if (index == ROW_OPTIONS_START) addSeparator()
            add(Toggle(field))
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** The eye shows pressed while the list hides threads (Only Threads Waiting for Me), so a filter is never on unseen. */
    override fun update(e: AnActionEvent) {
        Toggleable.setSelected(e.presentation, SkeinSettings.getInstance().state.onlyWaitingThreads)
    }

    private class Toggle(private val field: ViewOptions.Field) : DumbAwareToggleAction(field.text) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean = field.get(SkeinSettings.getInstance().state)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            field.set(SkeinSettings.getInstance().state, state)
            SkeinSettings.publishChanged()
        }
    }

    private companion object {
        /** "Show Last Message", "Show Time" and "Show Model and Context" change only how a row looks. */
        const val ROW_OPTIONS_START = 2
    }
}
