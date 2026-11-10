package io.github.v0rest.skein.ui

import io.github.v0rest.skein.ui.actions.MenuState
import io.github.v0rest.skein.ui.actions.RenameOutcome
import org.junit.Assert.assertEquals
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
}
