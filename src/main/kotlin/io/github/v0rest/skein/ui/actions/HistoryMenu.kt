package io.github.v0rest.skein.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.ui.HistoryRow

/**
 * The right-click menu of the History: open, pin, rename and done. [open] resumes the one selected session the way a
 * double click does.
 */
class HistoryMenu(
    private val project: Project,
    private val selected: () -> List<HistoryRow>,
    private val open: () -> Unit,
) {
    private val marks = SessionMarksService.getInstance(project)

    /**
     * One session at a time: each resume opens a tab with focus and may ask about a session running elsewhere, so a
     * multi-selection would open several focused tabs and ask several questions in a row. [open] reads the selection
     * again when it runs.
     */
    private val openAction = object : DumbAwareAction("Open") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected().size == 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (selected().size == 1) open()
        }
    }

    private val pin = object : DumbAwareAction("Pin") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val rows = selected()
            e.presentation.isEnabled = rows.isNotEmpty()
            e.presentation.text = MenuState.pinText(rows.map { it.pinned })
        }

        override fun actionPerformed(e: AnActionEvent) {
            val rows = selected()
            if (rows.isEmpty()) return
            val on = MenuState.pinText(rows.map { it.pinned }) == "Pin"
            marks.update { it.pin(rows.map { r -> r.entry.sessionId }, on) }
        }
    }

    private val rename = object : DumbAwareAction("Rename…") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected().size == 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            val entry = selected().singleOrNull()?.entry ?: return
            renameSession(project, marks, entry.sessionId, fallback = entry.title)
        }
    }

    private val done = object : DumbAwareAction("Mark as Done") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val rows = selected()
            e.presentation.isEnabled = rows.isNotEmpty()
            e.presentation.text = MenuState.doneText(rows.map { it.done })
        }

        override fun actionPerformed(e: AnActionEvent) {
            val rows = selected()
            if (rows.isEmpty()) return
            val ids = rows.map { it.entry.sessionId }
            if (MenuState.doneText(rows.map { it.done }) == "Mark as Done") marks.update { it.markDone(ids, System.currentTimeMillis()) } else marks.update { it.undone(ids) }
        }
    }

    fun group(): DefaultActionGroup = DefaultActionGroup(openAction, pin, rename, done)
}
