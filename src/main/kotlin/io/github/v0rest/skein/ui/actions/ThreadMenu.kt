package io.github.v0rest.skein.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupHandler
import io.github.v0rest.skein.threads.CloseRule
import io.github.v0rest.skein.threads.DisplayNames
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadColor
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.ShowChanges
import io.github.v0rest.skein.ui.WorktreeDiff
import javax.swing.JList

/** What the Rename dialog's answer means for the Skein name. */
sealed class RenameOutcome {
    /** The dialog was cancelled. */
    object Cancel : RenameOutcome()

    /** A blank answer: back to the usual name. */
    object Clear : RenameOutcome()

    /** OK on the pre-filled name: nothing changes, and an automatic name is not frozen into a Skein name. */
    object Keep : RenameOutcome()

    data class Set(val name: String) : RenameOutcome()
}

/** The wording of the context menus, which depends on what is selected. */
object MenuState {
    /** "Pin" unless every selected item is pinned already, then "Unpin". */
    fun pinText(selectedPinned: List<Boolean>): String = if (selectedPinned.isNotEmpty() && selectedPinned.all { it }) "Unpin" else "Pin"

    /** "Mark as Done" unless every selected item is done already, then "Mark as Undone". */
    fun doneText(selectedDone: List<Boolean>): String = if (selectedDone.isNotEmpty() && selectedDone.all { it }) "Mark as Undone" else "Mark as Done"

    /**
     * What to do with what the Rename dialog returned: [entered] is null when cancelled, [fallback] the name shown
     * when there is no Skein name (Claude Code's own, or the folder), [existing] the Skein name if there is one. OK
     * without a change keeps things as they are, so Claude Code's own name goes on following `/rename` and `/clear`.
     */
    fun renameOutcome(entered: String?, fallback: String, existing: String?): RenameOutcome {
        val text = entered?.trim() ?: return RenameOutcome.Cancel
        return when {
            text.isEmpty() -> RenameOutcome.Clear
            text == existing -> RenameOutcome.Keep
            existing == null && text == fallback -> RenameOutcome.Keep
            else -> RenameOutcome.Set(text)
        }
    }
}

/**
 * What the thread menu's Close asks first. Close ends the thread's claude right away and the IDE does not ask about a
 * Skein editor tab (a tab left in the Terminal tool window may also get the IDE's own question), so Skein asks when
 * claude is, or may be, in the middle of something in any of the threads ([CloseRule]); a thread between turns or
 * without its claude closes right away.
 */
object CloseQuestion {
    /** [ids]: the busy threads the question names, which the user confirms by answering Close. */
    data class Question(val title: String, val message: String, val ids: Set<String>)

    /**
     * The question before closing [threads], naming the busy ones ([CloseRule]) by the name the list shows ([names] are
     * Skein's, by session): those working first, then those whose state is unknown; null when none is busy. The title
     * counts the threads being closed.
     */
    fun of(threads: List<SkeinThread>, names: Map<String, String>): Question? {
        val working = threads.filter { CloseRule.busy(it.state) == CloseRule.Busy.WORKING }
        val maybe = threads.filter { CloseRule.busy(it.state) == CloseRule.Busy.MAYBE }
        if (working.isEmpty() && maybe.isEmpty()) return null
        fun named(busy: List<SkeinThread>) = busy.joinToString(", ") { DisplayNames.thread(it, names) }
        val sentences = buildList {
            if (working.isNotEmpty()) add("claude is still working in ${named(working)}.")
            if (maybe.isNotEmpty()) add("claude may still be working in ${named(maybe)}.")
            add("Closing ends what it is doing now; you can resume the session from the History later.")
        }
        val title = if (threads.size == 1) "Close Thread" else "Close Threads"
        return Question(title, sentences.joinToString(" "), (working + maybe).mapTo(LinkedHashSet()) { it.id })
    }
}

/** Asks for a Skein name; null when cancelled, blank to clear the name. */
internal fun askSkeinName(project: Project, current: String): String? = Messages.showInputDialog(
    project,
    "Name shown in Skein. Leave it empty to go back to the usual name.\nclaude -r keeps Claude Code's own name.",
    "Rename",
    null,
    current,
    null,
)

/** Asks for a Skein name for [sessionId] and applies the answer; [fallback] is the name shown when it has none. */
internal fun renameSession(project: Project, marks: SessionMarksService, sessionId: String, fallback: String) {
    val existing = marks.marks.value.names[sessionId]
    when (val outcome = MenuState.renameOutcome(askSkeinName(project, existing ?: fallback), fallback, existing)) {
        RenameOutcome.Cancel, RenameOutcome.Keep -> Unit
        RenameOutcome.Clear -> marks.update { it.rename(sessionId, null) }
        is RenameOutcome.Set -> marks.update { it.rename(sessionId, outcome.name) }
    }
}

/** The right-click menu of the thread list: pin, rename, colour, a worktree thread's Show Changes and close. */
class ThreadMenu(private val project: Project, private val list: JList<SkeinThread>) {
    private val service = ThreadService.getInstance(project)
    private val marks = SessionMarksService.getInstance(project)

    private fun selected(): List<SkeinThread> = list.selectedValuesList

    private val pin = object : DumbAwareAction("Pin") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val threads = selected()
            e.presentation.isEnabled = threads.isNotEmpty()
            e.presentation.text = MenuState.pinText(threads.map { it.pinned })
        }

        override fun actionPerformed(e: AnActionEvent) {
            val threads = selected()
            if (threads.isEmpty()) return
            service.setPinned(threads.map { it.id }, MenuState.pinText(threads.map { it.pinned }) == "Pin")
        }
    }

    private val rename = object : DumbAwareAction("Rename…") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        // a name belongs to a session, so a thread that has none yet cannot be named
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected().singleOrNull()?.sessionId != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val thread = selected().singleOrNull() ?: return
            val sessionId = thread.sessionId ?: return
            renameSession(project, marks, sessionId, fallback = thread.name)
        }
    }

    private inner class ColorAction(private val color: ThreadColor?, text: String) : DumbAwareToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = selected().let { it.isNotEmpty() && it.all { t -> t.color == color } }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val threads = selected()
            if (threads.isNotEmpty()) service.setColor(threads.map { it.id }, color)
        }
    }

    private val colors = DefaultActionGroup("Color", true).apply {
        ThreadColor.values().forEach { add(ColorAction(it, it.name.lowercase().replaceFirstChar(Char::uppercase))) }
        addSeparator()
        add(ColorAction(null, "None"))
    }

    /**
     * Ends the selected threads and closes their tabs the way their own close buttons do ([ThreadService.close]). For a
     * Skein editor tab that ends claude without a word from the IDE (a tab left in the Terminal tool window may also get
     * the IDE's own question), so it first asks when claude may be mid-turn in any of them ([CloseQuestion]) and passes
     * the threads the user confirmed; a worktree thread then asks whether to keep its worktree, as after the tab's own
     * close button.
     */
    private val close = object : DumbAwareAction("Close") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected().isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val threads = selected()
            if (threads.isEmpty()) return
            val question = CloseQuestion.of(threads, marks.marks.value.names)
            if (question != null && !confirmClose(question)) return
            service.close(threads.map { it.id }, confirmed = question?.ids.orEmpty())
        }
    }

    /**
     * The worktree's changes against the commit it shares with its base, in the IDE's diff viewer ([WorktreeDiff]):
     * there only for one selected worktree thread.
     */
    private val showChanges = object : DumbAwareAction("Show Changes") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = ShowChanges.worktreeThread(selected()) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val thread = ShowChanges.worktreeThread(selected()) ?: return
            WorktreeDiff.getInstance(project).show(thread.folder, thread.worktree ?: return, thread.base)
        }
    }

    /** Asks [question] on the EDT; true for Close. */
    private fun confirmClose(question: CloseQuestion.Question): Boolean =
        Messages.showYesNoDialog(project, question.message, question.title, "Close", "Cancel", Messages.getWarningIcon()) == Messages.YES

    fun install() {
        val group = DefaultActionGroup(pin, rename, colors, Separator.getInstance(), showChanges, Separator.getInstance(), close)
        PopupHandler.installPopupMenu(list, group, "SkeinThreadPopup")
    }
}
