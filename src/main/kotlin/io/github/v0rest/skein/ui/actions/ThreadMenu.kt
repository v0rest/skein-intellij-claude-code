package io.github.v0rest.skein.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupHandler
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.terminal.ClaudeRename
import io.github.v0rest.skein.threads.CloseRule
import io.github.v0rest.skein.threads.DisplayNames
import io.github.v0rest.skein.threads.RenameGate
import io.github.v0rest.skein.threads.SessionMarks
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadColor
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.RenameDialog
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

    /** [name] becomes the Skein name, and with [inClaude] it also waits to be typed into the session's claude. */
    data class Set(val name: String, val inClaude: Boolean = false) : RenameOutcome()
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

    /**
     * [renameOutcome] with the dialog's "Also rename in Claude Code" box ([inClaude]). OK on an unchanged Skein name still
     * sends it with the box, since the name may be in Skein and not yet in Claude Code, and without the box lets go of a
     * rename still waiting. An empty name clears only the Skein name and sends nothing, and an untouched automatic name
     * (Claude Code's own, an AI title, the folder) is not sent either, since nobody named anything.
     *
     * With the box disabled ([boxDisabled], Claude Code in vim mode) the user said nothing about Claude Code: OK on an
     * unchanged name keeps everything, a rename still waiting included, for when vim mode is gone. A new name is Skein's
     * only, and a rename still waiting goes then, since it is for the name the new one replaced.
     */
    fun renameOutcome(entered: String?, fallback: String, existing: String?, inClaude: Boolean, boxDisabled: Boolean = false): RenameOutcome =
        when (val plain = renameOutcome(entered, fallback, existing)) {
            is RenameOutcome.Set -> plain.copy(inClaude = inClaude && !boxDisabled)
            RenameOutcome.Keep -> if (existing != null && !boxDisabled) RenameOutcome.Set(existing, inClaude) else RenameOutcome.Keep
            RenameOutcome.Cancel, RenameOutcome.Clear -> plain
        }

    /** What the session's names were at one moment: its Skein name and the rename waiting for Claude Code, either may be none. */
    data class SessionNames(val skein: String?, val waiting: String?)

    /**
     * The answer of a Rename dialog that showed [shown] while the session's names were [before], given that they are
     * [after] when it is applied. The thread service may type the waiting rename, and Claude Code's record then drop the
     * Skein name, while the dialog is open; OK on the very text it showed then asks for nothing new, so it is Keep
     * rather than a second rename. Otherwise as [renameOutcome] on what the dialog showed.
     */
    fun renameAnswer(
        entered: String?,
        shown: String,
        fallback: String,
        before: SessionNames,
        after: SessionNames,
        inClaude: Boolean,
        boxDisabled: Boolean,
    ): RenameOutcome {
        if (entered != null && after != before && entered.trim() == shown.trim()) return RenameOutcome.Keep
        return renameOutcome(entered, fallback, before.skein, inClaude, boxDisabled)
    }

    /** [marks] with the dialog's answer ([renameAnswer]) applied to [sessionId], judged on what [marks] hold for it now. */
    fun applyRename(
        marks: SessionMarks,
        sessionId: String,
        entered: String?,
        shown: String,
        fallback: String,
        before: SessionNames,
        inClaude: Boolean,
        boxDisabled: Boolean,
    ): SessionMarks {
        val after = SessionNames(marks.names[sessionId], marks.claudeRenames[sessionId])
        return when (val outcome = renameAnswer(entered, shown, fallback, before, after, inClaude, boxDisabled)) {
            RenameOutcome.Cancel, RenameOutcome.Keep -> marks
            RenameOutcome.Clear -> marks.rename(sessionId, null)
            is RenameOutcome.Set -> marks.rename(sessionId, outcome.name, outcome.inClaude)
        }
    }

    /** How the dialog's "Also rename in Claude Code" box starts: ticked or not, usable or not, and the words under it. */
    data class RenameBox(val ticked: Boolean, val enabled: Boolean, val comment: String)

    /**
     * The box for a session with a rename [waiting] for Claude Code or none, the choice [remembered] from last time, and
     * Claude Code in [vimMode] or not. It is ticked while a rename waits, also in vim mode, where it cannot be changed
     * and its words say that the waiting rename goes once vim mode is off and that a changed or cleared name drops it.
     */
    fun renameBox(waiting: Boolean, remembered: Boolean, vimMode: Boolean): RenameBox = when {
        !vimMode -> RenameBox(ticked = waiting || remembered, enabled = true, comment = HOW)
        waiting -> RenameBox(ticked = true, enabled = false, comment = VIM_MODE_WAITING)
        else -> RenameBox(ticked = false, enabled = false, comment = VIM_MODE)
    }

    const val HOW = "Skein types /rename into claude once it is idle and nothing was typed in its tab since your last message " +
        "went, so claude -r shows the name too."

    const val VIM_MODE = "Claude Code is in vim mode, where typed text runs as commands, so Skein types nothing into it."

    const val VIM_MODE_WAITING = "Claude Code is in vim mode, where typed text runs as commands, so Skein types nothing into it " +
        "now. The name waiting for Claude Code goes once vim mode is off; changing or clearing the name here drops it."

    /** Why the box cannot be used for what was [entered] ([ClaudeRename.refusal]); null when it can, or nothing would be sent. */
    fun claudeNameProblem(entered: String, fallback: String, existing: String?, inClaude: Boolean): String? {
        val outcome = renameOutcome(entered, fallback, existing, inClaude) as? RenameOutcome.Set ?: return null
        if (!outcome.inClaude) return null
        return ClaudeRename.refusal(outcome.name)?.let { "$it Untick the box to use this name in Skein only." }
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

/**
 * Asks for a name for [sessionId] ([RenameDialog]) and applies the answer; [fallback] is the name shown when it has no
 * Skein name, [folders] where the session's claude runs, whose project settings may put Claude Code in vim mode. The
 * vim mode is read from files off the EDT first, and only the dialog comes back to it.
 */
internal fun renameSession(project: Project, marks: SessionMarksService, sessionId: String, fallback: String, folders: List<String>) {
    ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed) return@executeOnPooledThread
        val vimMode = ThreadService.getInstance(project).claudeInVimMode(folders)
        ApplicationManager.getApplication().invokeLater(
            { askAndRename(project, marks, sessionId, fallback, vimMode) },
            ModalityState.defaultModalityState(),
            project.disposed,
        )
    }
}

/**
 * The dialog and its answer, on the EDT. The box starts as [MenuState.renameBox] says: ticked when a rename still waits
 * for Claude Code, else as it was last left, and greyed out in [vimMode], ticked while a rename waits, where the choice
 * it was last left with is kept for later. With it the name also waits to be typed into the session's claude, which the thread service does once that is
 * safe. The answer is applied to the marks as they are once the dialog closed ([MenuState.applyRename]), since the
 * service may have typed the waiting rename meanwhile.
 */
private fun askAndRename(project: Project, marks: SessionMarksService, sessionId: String, fallback: String, vimMode: Boolean) {
    val current = marks.marks.value
    val before = MenuState.SessionNames(current.names[sessionId], current.claudeRenames[sessionId])
    val shown = before.skein ?: fallback
    val settings = SkeinSettings.getInstance().state
    val box = MenuState.renameBox(waiting = before.waiting != null, remembered = settings.renameInClaude, vimMode = vimMode)
    val dialog = RenameDialog(project, shown, box, fallback, before.skein)
    if (!dialog.showAndGet()) return
    if (box.enabled) settings.renameInClaude = dialog.inClaude
    marks.update { MenuState.applyRename(it, sessionId, dialog.entered, shown, fallback, before, dialog.inClaude, boxDisabled = !box.enabled) }
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
            renameSession(project, marks, sessionId, fallback = thread.name, folders = RenameGate.folders(thread))
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
