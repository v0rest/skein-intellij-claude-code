package io.github.v0rest.skein.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name Skein types after `/rename` into a running claude. The tab is a shell underneath, so the name must be plain
 * text to zsh, bash, fish, PowerShell and cmd alike: letters and digits of any script, spaces and `- _ . , : + # /`.
 */
class ClaudeRenameTest {
    @Test
    fun lettersDigitsSpacesAndTheFewMarksPass() {
        for (name in listOf("demo-shop", "task-1", "Fix login", "acme_api v2.1", "a,b:c+d#e/f", "x")) {
            assertTrue(name, ClaudeRename.isAllowed(name))
        }
    }

    @Test
    fun lettersOfAnyScriptPass() {
        assertTrue(ClaudeRename.isAllowed("Вхід через пошту"))
        assertTrue(ClaudeRename.isAllowed("Їжак і ґанок 2"))
        assertTrue(ClaudeRename.isAllowed("ログイン修正"))
        assertTrue(ClaudeRename.isAllowed("Ελληνικά ٣"))
    }

    @Test
    fun everyShellCharacterIsRefused() {
        val forbidden = listOf('"', '\'', '`', ';', '|', '&', '$', '!', '%', '^', '<', '>', '(', ')', '[', ']', '{', '}', '*', '?', '~', '=', '\\', '@', '\t', '\n', '\r', '\u0000', '\u001b', '\u007f', ' ', '​')
        for (c in forbidden) {
            val name = "demo${c}shop"
            assertFalse("U+%04X".format(c.code), ClaudeRename.isAllowed(name))
            assertNotNull(ClaudeRename.refusal(name))
        }
    }

    @Test
    fun theNameIsTrimmedFirst() {
        assertTrue(ClaudeRename.isAllowed("  demo-shop  "))
        assertEquals("/rename demo-shop", ClaudeRename.line("  demo-shop  "))
    }

    @Test
    fun aBlankNameIsRefused() {
        assertFalse(ClaudeRename.isAllowed(""))
        assertFalse(ClaudeRename.isAllowed("   "))
    }

    @Test
    fun aHundredCharactersAtMost() {
        assertTrue(ClaudeRename.isAllowed("a".repeat(100)))
        assertFalse(ClaudeRename.isAllowed("a".repeat(101)))
        // characters as a person counts them, so a letter outside the BMP is one, not two
        assertTrue(ClaudeRename.isAllowed("𝐀".repeat(100)))
        assertEquals("trimmed before it is counted", true, ClaudeRename.isAllowed(" " + "a".repeat(100) + " "))
    }

    @Test
    fun theRefusalSaysWhy() {
        assertNull(ClaudeRename.refusal("demo-shop"))
        assertTrue(ClaudeRename.refusal("demo (shop)")!!.contains("\"(\""))
        assertTrue(ClaudeRename.refusal("demo\tshop")!!.contains("U+0009"))
        assertTrue(ClaudeRename.refusal("a".repeat(120))!!.contains("100"))
    }

    @Test
    fun theLineIsTheCommandAndTheName() {
        assertEquals("/rename task-1", ClaudeRename.line("task-1"))
        assertEquals("/rename Вхід через пошту", ClaudeRename.line("Вхід через пошту"))
    }

    @Test
    fun aRefusedNameNeverBecomesALine() {
        assertThrows(IllegalArgumentException::class.java) { ClaudeRename.line("demo; rm -rf ~") }
        assertThrows(IllegalArgumentException::class.java) { ClaudeRename.line("") }
        assertThrows(IllegalArgumentException::class.java) { ClaudeRename.line("a".repeat(101)) }
    }

    /** Right before the line goes: an open tab Skein is not closing, its session running, its input clean - all of it. */
    @Test
    fun theTabTakesTheLineOnlyWhenEveryPartHolds() {
        assertTrue(ClaudeRename.tabReady(disposed = false, ending = false, released = false, running = true, inputClean = true))
        assertFalse(ClaudeRename.tabReady(disposed = true, ending = false, released = false, running = true, inputClean = true))
        assertFalse(ClaudeRename.tabReady(disposed = false, ending = true, released = false, running = true, inputClean = true))
        assertFalse(ClaudeRename.tabReady(disposed = false, ending = false, released = true, running = true, inputClean = true))
        assertFalse(ClaudeRename.tabReady(disposed = false, ending = false, released = false, running = false, inputClean = true))
        assertFalse(ClaudeRename.tabReady(disposed = false, ending = false, released = false, running = true, inputClean = false))
    }

    @Test
    fun theTranscriptConfirmsTheNameByItsLatestCustomTitle() {
        val renamed = """{"type":"custom-title","customTitle":"task-1","sessionId":"s"}"""
        val renamedAgain = """{"type":"custom-title","customTitle":"demo-shop","sessionId":"s"}"""
        val note = """{"type":"user","message":{"role":"user","content":"named"}}"""
        assertTrue(ClaudeRename.confirmedBy(listOf(note, renamed, note), "task-1"))
        assertTrue(ClaudeRename.confirmedBy(listOf(renamed), "  task-1 "))
        assertFalse("a later rename wins", ClaudeRename.confirmedBy(listOf(renamed, renamedAgain), "task-1"))
        assertFalse(ClaudeRename.confirmedBy(listOf(note), "task-1"))
        assertFalse(ClaudeRename.confirmedBy(emptyList(), "task-1"))
        // an AI title or a last prompt that reads the same is not Claude Code's own name
        assertFalse(ClaudeRename.confirmedBy(listOf("""{"type":"ai-title","aiTitle":"task-1"}"""), "task-1"))
    }
}
