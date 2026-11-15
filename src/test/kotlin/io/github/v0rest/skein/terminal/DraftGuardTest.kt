package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.terminal.DraftGuard.Draft
import io.github.v0rest.skein.terminal.DraftGuard.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * The draft guard: Skein cannot see claude's input box, and a `/rename` typed after a draft goes to the model with it as
 * one message, so a tab counts as clean only when nothing was typed since an Enter that a turn right after it showed
 * sent what was there, and no key came for a while. A claude Skein just started counts as dirty until that first turn.
 */
class DraftGuardTest {
    private fun pressed(code: Int, modifiers: Int = 0) = DraftGuard.classify(KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, modifiers)

    private fun typed(char: Char, modifiers: Int = 0) = DraftGuard.classify(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, char, modifiers)

    @Test
    fun aPlainEnterIsEnterAndItsTypedHalfIsNothing() {
        assertEquals(Key.ENTER, pressed(KeyEvent.VK_ENTER))
        assertEquals(Key.NONE, typed('\n'))
        assertEquals(Key.NONE, typed('\r'))
        assertEquals(Key.NONE, DraftGuard.classify(KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER, '\n', 0))
    }

    /** Shift, Alt, Ctrl or Cmd with Enter makes a new line in claude's input, it sends nothing. */
    @Test
    fun anEnterWithAModifierIsAnotherKey() {
        for (modifier in listOf(InputEvent.SHIFT_DOWN_MASK, InputEvent.ALT_DOWN_MASK, InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK, InputEvent.ALT_GRAPH_DOWN_MASK)) {
            assertEquals(Key.OTHER, pressed(KeyEvent.VK_ENTER, modifier))
        }
    }

    /** A modifier or lock key alone puts nothing into the input, so Cmd-Tab away from a tab leaves it as it was. */
    @Test
    fun aModifierAloneIsNothing() {
        for (code in listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_WINDOWS)) {
            assertEquals(Key.NONE, pressed(code, InputEvent.META_DOWN_MASK))
        }
        assertEquals(Key.NONE, DraftGuard.classify(KeyEvent.KEY_RELEASED, KeyEvent.VK_A, 'a', 0))
    }

    @Test
    fun everyOtherKeyCounts() {
        assertEquals(Key.OTHER, pressed(KeyEvent.VK_A))
        assertEquals(Key.OTHER, typed('a'))
        assertEquals(Key.OTHER, typed('ї'))
        assertEquals(Key.OTHER, pressed(KeyEvent.VK_UP))
        assertEquals(Key.OTHER, pressed(KeyEvent.VK_ESCAPE))
        assertEquals(Key.OTHER, pressed(KeyEvent.VK_BACK_SPACE))
        assertEquals(Key.OTHER, pressed(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))
        assertEquals(Key.OTHER, typed('\b'))
        assertEquals(Key.BACKSLASH, typed('\\'))
    }

    /** One key at [at] on the steady clock and [wall] on the wall clock; an Enter there may send the input when [maySend]. */
    private fun Draft.then(key: Key, at: Long, wall: Long = at, maySend: Boolean = true) = DraftGuard.next(this, key, maySend, at, wall)

    /** [DraftGuard.settled] by a turn seen starting at [turnAt] (wall clock), as the key watch hears of it. */
    private fun Draft.afterTurn(turnAt: Long?) = DraftGuard.settled(this, DraftGuard.proven(this, turnAt, null))

    @Test
    fun anEmptyInputIsClean() {
        assertTrue(Draft().clean)
        assertTrue(DraftGuard.allowsSend(Draft(), proven = null, now = 0))
        assertTrue(DraftGuard.inputClean(Draft(), proven = null, now = 0))
    }

    /** A tab the key watch could not be added to: nobody can tell what its input holds, so it is never clean. */
    @Test
    fun anUnwatchedTabIsNeverClean() {
        assertFalse(DraftGuard.inputClean(null, proven = null, now = 100_000))
        assertFalse(DraftGuard.inputClean(null, proven = 5, now = 100_000))
    }

    /**
     * A claude Skein has just started may ask something first while its status already says idle, so the tab starts
     * dirty and the first turn the person starts makes it clean.
     */
    @Test
    fun aJustStartedClaudeIsDirtyUntilTheFirstTurn() {
        assertFalse(DraftGuard.LAUNCHED.clean)
        assertFalse(DraftGuard.allowsSend(DraftGuard.LAUNCHED, proven = null, now = 100_000))
        assertFalse("an Enter on a startup question starts no turn", DraftGuard.LAUNCHED.then(Key.ENTER, at = 1_000, wall = 50_000).afterTurn(null).clean)
        val prompt = DraftGuard.LAUNCHED.then(Key.OTHER, at = 1_000).then(Key.ENTER, at = 2_000, wall = 50_000)
        assertTrue(prompt.afterTurn(51_500).clean)
    }

    /**
     * A key makes it dirty, and an Enter on the draft while claude is idle makes it clean only once a turn is seen
     * starting soon after that Enter: claude went to work, so the draft went.
     */
    @Test
    fun aKeyMakesItDirtyAndOnlyATurnRightAfterTheEnterMakesItCleanAgain() {
        val typed = Draft().then(Key.OTHER, at = 1_000)
        assertFalse(typed.clean)
        val entered = typed.then(Key.ENTER, at = 2_000, wall = 50_000)
        assertFalse("an Enter alone proves nothing", entered.clean)
        assertEquals(50_000L, entered.sentAt)
        assertFalse("a turn seen before the Enter is not this one", entered.afterTurn(49_000).clean)
        assertFalse(entered.afterTurn(null).clean)
        val settled = entered.afterTurn(50_500)
        assertTrue(settled.clean)
        assertEquals(null, settled.sentAt)
        assertTrue("a key that is nothing changes nothing", settled.then(Key.NONE, at = 3_000).clean)
    }

    /**
     * A turn claude starts on its own later (a background agent that finished, a scheduled wakeup) says nothing about the
     * Enter: only one seen within the window after it counts.
     */
    @Test
    fun aTurnLongAfterTheEnterProvesNothing() {
        assertTrue(DraftGuard.turnProves(sentAt = 50_000, turnStartedAt = 50_001))
        assertTrue(DraftGuard.turnProves(sentAt = 50_000, turnStartedAt = 50_000 + DraftGuard.TURN_WINDOW_MILLIS))
        assertFalse(DraftGuard.turnProves(sentAt = 50_000, turnStartedAt = 50_000 + DraftGuard.TURN_WINDOW_MILLIS + 1))
        assertFalse(DraftGuard.turnProves(sentAt = 50_000, turnStartedAt = 50_000))
        assertFalse(DraftGuard.turnProves(sentAt = 50_000, turnStartedAt = 49_000))
        val entered = Draft().then(Key.OTHER, at = 1_000).then(Key.ENTER, at = 2_000, wall = 50_000)
        assertFalse(entered.afterTurn(50_000 + DraftGuard.TURN_WINDOW_MILLIS + 1).clean)
    }

    /** What is proven sticks to that one Enter: an earlier proof stays for it, a newer Enter needs its own. */
    @Test
    fun aProofBelongsToItsEnter() {
        val entered = Draft().then(Key.OTHER, at = 1_000).then(Key.ENTER, at = 2_000, wall = 50_000)
        val proven = DraftGuard.proven(entered, atWorkSince = 51_000, proven = null)
        assertEquals(50_000L, proven)
        assertEquals("a turn seen later keeps the proof", 50_000L, DraftGuard.proven(entered, atWorkSince = 90_000, proven = proven))
        assertEquals("no draft, nothing new", 7L, DraftGuard.proven(null, atWorkSince = 51_000, proven = 7L))
        val again = entered.then(Key.OTHER, at = 3_000, wall = 60_000).then(Key.ENTER, at = 4_000, wall = 70_000)
        assertFalse("the older proof is not the newer Enter's", DraftGuard.settled(again, proven).clean)
    }

    /**
     * An Enter on a suggestion or on a command that opens a menu (`/config`) starts no turn, and what it left in the input
     * or on the screen would take `/rename` with it - so the tab stays dirty. A key typed after the Enter starts over.
     */
    @Test
    fun anEnterThatStartsNoTurnLeavesItDirty() {
        val entered = Draft().then(Key.OTHER, at = 1_000).then(Key.ENTER, at = 2_000, wall = 50_000)
        assertFalse(DraftGuard.allowsSend(entered, proven = null, now = 100_000))
        val typedAgain = entered.then(Key.OTHER, at = 3_000, wall = 51_000)
        assertEquals(null, typedAgain.sentAt)
        assertFalse("the turn may be the earlier message's, the new key is still there", typedAgain.afterTurn(52_000).clean)
    }

    /** `\` and Enter is claude's own way to start a new line, so the draft stays. */
    @Test
    fun anEnterRightAfterABackslashSendsNothing() {
        var draft = Draft()
        for (key in listOf(Key.OTHER, Key.BACKSLASH, Key.ENTER)) draft = draft.then(key, at = 1_000)
        assertEquals(null, draft.sentAt)
        assertFalse(draft.afterTurn(1_500).clean)
        // a modifier between the two changes nothing, a real key in between does
        draft = Draft()
        for (key in listOf(Key.BACKSLASH, Key.NONE, Key.ENTER)) draft = draft.then(key, at = 1_000)
        assertEquals(null, draft.sentAt)
        draft = Draft()
        for (key in listOf(Key.BACKSLASH, Key.OTHER, Key.ENTER)) draft = draft.then(key, at = 1_000)
        assertEquals(1_000L, draft.sentAt)
        assertTrue(draft.afterTurn(1_001).clean)
    }

    /**
     * While claude asks something (a permission prompt) its Enter answers that, and while it works an Enter may take a
     * suggestion into the draft: the draft stays, whatever turn comes later. A clean input stays clean, since an Enter
     * adds nothing to it.
     */
    @Test
    fun anEnterWhileClaudeIsNotIdleLeavesTheDraftAsItWas() {
        val dirty = Draft().then(Key.OTHER, at = 1_000)
        val answered = dirty.then(Key.ENTER, at = 2_000, maySend = false)
        assertEquals(null, answered.sentAt)
        assertFalse(answered.afterTurn(2_500).clean)
        assertTrue(Draft().then(Key.ENTER, at = 2_000, maySend = false).clean)
        assertTrue(Draft().then(Key.ENTER, at = 2_000, maySend = true).clean)
    }

    /** No `/rename` lands among keys a person is typing right now, whatever the guard says of them. */
    @Test
    fun aSendWaitsForTheKeysToSettle() {
        val sent = Draft().then(Key.OTHER, at = 10_000).then(Key.ENTER, at = 11_000, wall = 500)
        assertFalse(DraftGuard.allowsSend(sent, proven = 500, now = 11_000))
        assertFalse(DraftGuard.allowsSend(sent, proven = 500, now = 11_000 + DraftGuard.SETTLE_MILLIS - 1))
        assertTrue(DraftGuard.allowsSend(sent, proven = 500, now = 11_000 + DraftGuard.SETTLE_MILLIS))
        val emptyEnter = Draft().then(Key.ENTER, at = 10_000)
        assertFalse(DraftGuard.allowsSend(emptyEnter, proven = null, now = 10_000 + DraftGuard.SETTLE_MILLIS - 1))
        val dirty = Draft().then(Key.OTHER, at = 10_000)
        assertFalse(DraftGuard.allowsSend(dirty, proven = 999_999, now = 100_000))
    }

    /** Paste, Escape and the terminal's own insertions are IDE actions, not keys; the terminal's look-only ones and the IDE's own are not. */
    @Test
    fun actionsThatMayChangeTheInputCountAsAKey() {
        for (id in listOf("Terminal.Paste", "Terminal.PasteFromHistory", "Terminal.PasteSelection", "\$Paste", "PasteMultiple", "Terminal.Escape", "Terminal.CommandCompletion", "Terminal.CommandCompletion.InsertSuggestion", "Terminal.InsertInlineCompletion", "Terminal.SomethingNew", null)) {
            assertTrue(id.toString(), DraftGuard.touchesInput(id))
        }
        for (id in listOf("Terminal.Find", "Terminal.CopySelectedText", "Terminal.CopyBlock", "\$Copy", "Terminal.LineUp", "Terminal.PageDown", "Terminal.MoveToEditor", "Terminal.RenameSession", "Terminal.SwitchFocusToEditor", "Terminal.CloseTab", "RecentFiles", "SearchEverywhere", "ActivateProjectToolWindow")) {
            assertFalse(id, DraftGuard.touchesInput(id))
        }
    }
}
