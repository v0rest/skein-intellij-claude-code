package io.github.v0rest.skein.threads

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMarksServiceTest {
    private val a = "55555555-5555-4555-8555-555555555555"
    private val b = "66666666-6666-4666-8666-666666666666"
    private val c = "77777777-7777-4777-8777-777777777777"

    @Test
    fun stateRoundTripKeepsPinsNamesAndDone() {
        val marks = SessionMarks(pinned = setOf(a), names = mapOf(b to "Fix login & <co>"), done = mapOf(c to 42L))
        val service = SessionMarksService().apply { update { marks } }
        val fresh = SessionMarksService().apply { loadState(service.state) }
        assertEquals(marks, fresh.marks.value)
    }

    /** A rename that waits for Claude Code survives an IDE restart, so a History session gets it when it is next opened. */
    @Test
    fun aRenameThatWaitsForClaudeCodeIsSavedToo() {
        val marks = SessionMarks(names = mapOf(a to "Вхід через пошту", b to "task-1"), claudeRenames = mapOf(a to "Вхід через пошту"))
        val service = SessionMarksService().apply { update { marks } }
        val element = XmlSerializer.serialize(service.state)
        val fresh = SessionMarksService().apply { loadState(XmlSerializer.deserialize(element, SessionMarksService.Saved::class.java)) }
        assertEquals(marks, fresh.marks.value)
    }

    /**
     * Right before the line goes the waiting rename is taken: only when it still waits as that name, which it then stops
     * doing. One the Rename dialog dropped or replaced meanwhile is not taken, and the marks stay as the dialog left them.
     */
    @Test
    fun onlyARenameThatStillWaitsIsTakenForTheLine() {
        val service = SessionMarksService().apply { update { it.rename(a, "task-1", inClaude = true) } }
        assertEquals(true, service.claimClaudeRename(a, "task-1"))
        assertEquals(emptyMap<String, String>(), service.marks.value.claudeRenames)
        assertEquals("the Skein name stays until Claude Code shows it", mapOf(a to "task-1"), service.marks.value.names)
        assertEquals("taken once", false, service.claimClaudeRename(a, "task-1"))

        val replaced = SessionMarksService().apply { update { it.rename(a, "task-1", inClaude = true).rename(a, "demo-shop", inClaude = true) } }
        assertEquals(false, replaced.claimClaudeRename(a, "task-1"))
        assertEquals(mapOf(a to "demo-shop"), replaced.marks.value.claudeRenames)

        val dropped = SessionMarksService().apply { update { it.rename(a, "task-1", inClaude = true).rename(a, "task-1") } }
        val before = dropped.marks.value
        assertEquals(false, dropped.claimClaudeRename(a, "task-1"))
        assertEquals(before, dropped.marks.value)
    }

    /** A waiting rename is always the Skein name too; a saved one that is not (a hand-edited file) is not loaded. */
    @Test
    fun aWaitingRenameWithoutItsSkeinNameIsNotLoaded() {
        val saved = SessionMarksService.Saved().apply {
            names.putAll(mapOf(a to "task-1", b to "demo-shop"))
            claudeRenames.putAll(mapOf(a to "task-1", b to "other", c to "task-2"))
        }
        val fresh = SessionMarksService().apply { loadState(saved) }
        assertEquals(mapOf(a to "task-1"), fresh.marks.value.claudeRenames)
    }

    @Test
    fun theSavedStateSurvivesTheXmlTheWorkspaceFileHolds() {
        val marks = SessionMarks(pinned = setOf(a), names = mapOf(b to "Fix login & <co>"), done = mapOf(c to 42L))
        val service = SessionMarksService().apply { update { marks } }
        val element = XmlSerializer.serialize(service.state)
        val fresh = SessionMarksService().apply { loadState(XmlSerializer.deserialize(element, SessionMarksService.Saved::class.java)) }
        assertEquals(marks, fresh.marks.value)
    }
}
