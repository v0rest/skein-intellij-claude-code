package io.github.v0rest.skein.ui

import io.github.v0rest.skein.threads.SessionMarks
import io.github.v0rest.skein.ui.actions.MenuState
import io.github.v0rest.skein.ui.actions.RenameOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuStateTest {
    @Test
    fun pinTextIsUnpinOnlyWhenEverySelectedThreadIsPinned() {
        assertEquals("Pin", MenuState.pinText(listOf(false)))
        assertEquals("Pin", MenuState.pinText(listOf(true, false)))
        assertEquals("Pin", MenuState.pinText(emptyList()))
        assertEquals("Unpin", MenuState.pinText(listOf(true)))
        assertEquals("Unpin", MenuState.pinText(listOf(true, true)))
    }

    @Test
    fun doneTextFollowsTheSelection() {
        assertEquals("Mark as Done", MenuState.doneText(listOf(false)))
        assertEquals("Mark as Done", MenuState.doneText(listOf(true, false)))
        assertEquals("Mark as Done", MenuState.doneText(emptyList()))
        assertEquals("Mark as Undone", MenuState.doneText(listOf(true, true)))
    }

    // the dialog is pre-filled with the name shown now: Claude Code's own when no Skein name exists, else the Skein name

    @Test
    fun okWithoutChangeKeepsTheAutomaticName() {
        assertEquals(RenameOutcome.Keep, MenuState.renameOutcome(entered = "acme", fallback = "acme", existing = null))
        assertEquals(RenameOutcome.Keep, MenuState.renameOutcome(entered = "  acme ", fallback = "acme", existing = null))
    }

    @Test
    fun okWithTheExistingSkeinNameChangesNothing() {
        assertEquals(RenameOutcome.Keep, MenuState.renameOutcome(entered = "Fix login", fallback = "acme", existing = "Fix login"))
    }

    @Test
    fun aBlankClearsTheSkeinName() {
        assertEquals(RenameOutcome.Clear, MenuState.renameOutcome(entered = "", fallback = "acme", existing = "Fix login"))
        assertEquals(RenameOutcome.Clear, MenuState.renameOutcome(entered = "   ", fallback = "acme", existing = null))
    }

    @Test
    fun aNewTextSetsTheSkeinName() {
        assertEquals(RenameOutcome.Set("Fix login"), MenuState.renameOutcome(entered = " Fix login ", fallback = "acme", existing = null))
        assertEquals(RenameOutcome.Set("Fix auth"), MenuState.renameOutcome(entered = "Fix auth", fallback = "acme", existing = "Fix login"))
        // typing the automatic name over a Skein name is a wish to show exactly that, so it is set, not dropped
        assertEquals(RenameOutcome.Set("acme"), MenuState.renameOutcome(entered = "acme", fallback = "acme", existing = "Fix login"))
    }

    @Test
    fun cancelDoesNothing() {
        assertEquals(RenameOutcome.Cancel, MenuState.renameOutcome(entered = null, fallback = "acme", existing = "Fix login"))
    }

    // with "Also rename in Claude Code" the same answers, and the box decides whether the name also goes to Claude Code

    @Test
    fun theBoxSendsANewName() {
        assertEquals(RenameOutcome.Set("task-1", inClaude = true), MenuState.renameOutcome(" task-1 ", fallback = "acme", existing = null, inClaude = true))
        assertEquals(RenameOutcome.Set("task-1", inClaude = false), MenuState.renameOutcome("task-1", fallback = "acme", existing = "demo-shop", inClaude = false))
    }

    /** The Skein name is there already and only Claude Code lacks it: OK with the box sends it all the same. */
    @Test
    fun theBoxSendsAnUnchangedSkeinName() {
        assertEquals(RenameOutcome.Set("task-1", inClaude = true), MenuState.renameOutcome("task-1", fallback = "acme", existing = "task-1", inClaude = true))
        // without the box the Skein name stays as it is, and a name still waiting for Claude Code is let go
        assertEquals(RenameOutcome.Set("task-1", inClaude = false), MenuState.renameOutcome("task-1", fallback = "acme", existing = "task-1", inClaude = false))
    }

    /** No Skein name and the shown name untouched: nothing was named, so not even the box freezes a folder or an AI title. */
    @Test
    fun theBoxLeavesAnUntouchedAutomaticNameAlone() {
        assertEquals(RenameOutcome.Keep, MenuState.renameOutcome("acme", fallback = "acme", existing = null, inClaude = true))
    }

    @Test
    fun anEmptyNameClearsOnlyTheSkeinNameWhateverTheBox() {
        assertEquals(RenameOutcome.Clear, MenuState.renameOutcome("", fallback = "acme", existing = "task-1", inClaude = true))
        assertEquals(RenameOutcome.Cancel, MenuState.renameOutcome(null, fallback = "acme", existing = "task-1", inClaude = true))
    }

    /**
     * In vim mode the box is greyed out, ticked while a rename waits, and the user says nothing about Claude Code: OK on
     * an unchanged name keeps a rename that still waits, for when vim mode is gone; a new name is Skein's only and lets
     * the waiting one go, since that was for the name it replaced; an empty one clears both, as always.
     */
    @Test
    fun withTheBoxDisabledAWaitingRenameStaysUntilANewerNameReplacesIt() {
        val s = "55555555-5555-4555-8555-555555555555"
        val waiting = SessionMarks().rename(s, "task-1", inClaude = true)
        fun apply(outcome: RenameOutcome) = when (outcome) {
            RenameOutcome.Cancel, RenameOutcome.Keep -> waiting
            RenameOutcome.Clear -> waiting.rename(s, null)
            is RenameOutcome.Set -> waiting.rename(s, outcome.name, outcome.inClaude)
        }
        // a greyed-out box hands on inClaude = false, ticked or not
        val unchanged = MenuState.renameOutcome("task-1", fallback = "acme", existing = "task-1", inClaude = false, boxDisabled = true)
        assertEquals(RenameOutcome.Keep, unchanged)
        assertEquals(mapOf(s to "task-1"), apply(unchanged).claudeRenames)
        val renamed = MenuState.renameOutcome("demo-shop", fallback = "acme", existing = "task-1", inClaude = true, boxDisabled = true)
        assertEquals(RenameOutcome.Set("demo-shop", inClaude = false), renamed)
        assertEquals(mapOf(s to "demo-shop"), apply(renamed).names)
        assertEquals(emptyMap<String, String>(), apply(renamed).claudeRenames)
        assertEquals(RenameOutcome.Clear, MenuState.renameOutcome("", fallback = "acme", existing = "task-1", inClaude = false, boxDisabled = true))
        // an untouched automatic name stays untouched as well
        assertEquals(RenameOutcome.Keep, MenuState.renameOutcome("acme", fallback = "acme", existing = null, inClaude = false, boxDisabled = true))
        // and with the box on, as before, an unticked OK lets the waiting rename go
        assertEquals(RenameOutcome.Set("task-1", inClaude = false), MenuState.renameOutcome("task-1", fallback = "acme", existing = "task-1", inClaude = false))
    }

    /**
     * The box shows what will happen: ticked while a rename waits for Claude Code, else as last left; in vim mode it
     * cannot be changed, stays ticked while a rename waits, and its words say when that one goes and what drops it.
     */
    @Test
    fun theBoxStartsAsWhatWillHappen() {
        assertEquals(MenuState.RenameBox(ticked = true, enabled = true, comment = MenuState.HOW), MenuState.renameBox(waiting = true, remembered = false, vimMode = false))
        assertEquals(MenuState.RenameBox(ticked = true, enabled = true, comment = MenuState.HOW), MenuState.renameBox(waiting = false, remembered = true, vimMode = false))
        assertEquals(MenuState.RenameBox(ticked = false, enabled = true, comment = MenuState.HOW), MenuState.renameBox(waiting = false, remembered = false, vimMode = false))
        val vimWaiting = MenuState.renameBox(waiting = true, remembered = false, vimMode = true)
        assertEquals(MenuState.RenameBox(ticked = true, enabled = false, comment = MenuState.VIM_MODE_WAITING), vimWaiting)
        assertTrue(vimWaiting.comment.contains("once vim mode is off") && vimWaiting.comment.contains("changing or clearing the name here drops it"))
        assertEquals(MenuState.RenameBox(ticked = false, enabled = false, comment = MenuState.VIM_MODE), MenuState.renameBox(waiting = false, remembered = true, vimMode = true))
    }

    private val session = "55555555-5555-4555-8555-555555555555"

    /** One Rename dialog: it showed [shown] with the names [before], and its answer meets the marks [now]. */
    private fun answer(now: SessionMarks, entered: String?, before: MenuState.SessionNames, shown: String = before.skein ?: "acme", inClaude: Boolean = true, boxDisabled: Boolean = false) =
        MenuState.applyRename(now, session, entered, shown, fallback = "acme", before = before, inClaude = inClaude, boxDisabled = boxDisabled)

    /**
     * While the dialog was open the service typed the waiting rename and Claude Code's record dropped the Skein name. OK
     * on the very text the dialog showed asks for nothing new: no Skein name comes back and nothing waits again, so no
     * second `/rename` goes.
     */
    @Test
    fun okOnTheShownTextAfterTheRenameWentMeanwhileChangesNothing() {
        val before = MenuState.SessionNames(skein = "task-1", waiting = "task-1")
        val sentAndConfirmed = SessionMarks()
        assertEquals(sentAndConfirmed, answer(sentAndConfirmed, "task-1", before))
        // sent, not confirmed yet
        val sent = SessionMarks(names = mapOf(session to "task-1"))
        assertEquals(sent, answer(sent, " task-1 ", before))
        // without the race the same OK sends the name again, as the owner's case wants
        val unchanged = SessionMarks(names = mapOf(session to "task-1"), claudeRenames = mapOf(session to "task-1"))
        assertEquals(unchanged, answer(unchanged, "task-1", before))
        val notSentYet = SessionMarks(names = mapOf(session to "task-1"))
        assertEquals(mapOf(session to "task-1"), answer(notSentYet, "task-1", MenuState.SessionNames("task-1", null)).claudeRenames)
    }

    /**
     * Nothing waited when the dialog opened, but a rename sent earlier was confirmed meanwhile and the Skein name went:
     * OK on the shown text with the box ticked brings neither the Skein name nor a waiting rename back.
     */
    @Test
    fun okOnTheShownTextAfterAConfirmationMeanwhileChangesNothing() {
        val before = MenuState.SessionNames(skein = "task-1", waiting = null)
        val confirmed = SessionMarks()
        assertEquals(confirmed, answer(confirmed, "task-1", before, inClaude = true))
    }

    /** A newer rename came while this dialog was open (another dialog, the History): OK on the shown text leaves it be. */
    @Test
    fun okOnTheShownTextAfterANewerRenameMeanwhileLeavesThatOne() {
        val before = MenuState.SessionNames(skein = "task-1", waiting = null)
        val newer = SessionMarks(names = mapOf(session to "task-2"), claudeRenames = mapOf(session to "task-2"))
        assertEquals(newer, answer(newer, "task-1", before, inClaude = true))
    }

    /** A text the user changed is their wish whatever happened meanwhile; Cancel is Cancel. */
    @Test
    fun aChangedTextStillCountsAfterAChangeMeanwhile() {
        val before = MenuState.SessionNames(skein = "task-1", waiting = "task-1")
        val renamed = answer(SessionMarks(), "demo-shop", before)
        assertEquals(mapOf(session to "demo-shop"), renamed.names)
        assertEquals(mapOf(session to "demo-shop"), renamed.claudeRenames)
        assertEquals(SessionMarks(), answer(SessionMarks(), "", before))
        val sent = SessionMarks(names = mapOf(session to "task-1"))
        assertEquals(sent, answer(sent, null, before))
    }

    /** In vim mode the same: OK on the shown text keeps the waiting rename, a new name drops it. */
    @Test
    fun inVimModeTheAnswerKeepsOrDropsTheWaitingRenameAsTheWordsSay() {
        val before = MenuState.SessionNames(skein = "task-1", waiting = "task-1")
        val waiting = SessionMarks(names = mapOf(session to "task-1"), claudeRenames = mapOf(session to "task-1"))
        assertEquals(waiting, answer(waiting, "task-1", before, inClaude = false, boxDisabled = true))
        assertEquals(emptyMap<String, String>(), answer(waiting, "demo-shop", before, inClaude = false, boxDisabled = true).claudeRenames)
        assertEquals(SessionMarks(), answer(waiting, "", before, inClaude = false, boxDisabled = true))
    }

    /** The dialog says why the box cannot be used for a name the shell under claude would read, and only then. */
    @Test
    fun theDialogRefusesTheBoxOnlyForANameItWouldSend() {
        assertEquals(null, MenuState.claudeNameProblem("task-1", fallback = "acme", existing = null, inClaude = true))
        assertEquals(null, MenuState.claudeNameProblem("demo (shop)", fallback = "acme", existing = null, inClaude = false))
        assertEquals(null, MenuState.claudeNameProblem("", fallback = "acme", existing = "task-1", inClaude = true))
        // an untouched automatic name is not sent, so its brackets do not matter
        assertEquals(null, MenuState.claudeNameProblem("Fix (login)", fallback = "Fix (login)", existing = null, inClaude = true))
        val problem = MenuState.claudeNameProblem("demo (shop)", fallback = "acme", existing = null, inClaude = true)
        assertEquals(true, problem != null && problem.contains("\"(\""))
        assertEquals(true, MenuState.claudeNameProblem("a".repeat(101), fallback = "acme", existing = null, inClaude = true) != null)
    }
}
