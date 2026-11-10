package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.threads.SessionMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryViewTest {
    private fun entry(id: String, last: Long) = HistoryEntry(id, "/work/alpha", "title $id", last, null)

    private val a = entry("a", 100)
    private val b = entry("b", 300)
    private val c = entry("c", 200)
    private val d = entry("d", 400)

    @Test fun pinnedSessionsComeFirstNewestFirstAmongThem() {
        val rows = HistoryView.arrange(listOf(d, b, c, a), SessionMarks(pinned = setOf("a", "c")), showDone = false)
        assertEquals(listOf("c", "a", "d", "b"), rows.map { it.entry.sessionId })
        assertEquals(listOf(true, true, false, false), rows.map { it.pinned })
    }

    @Test fun doneSessionsAreHiddenUnlessShowDone() {
        val marks = SessionMarks(done = mapOf("b" to 300L))
        assertEquals(listOf("d", "c", "a"), HistoryView.arrange(listOf(d, b, c, a), marks, showDone = false).map { it.entry.sessionId })
        val shown = HistoryView.arrange(listOf(d, b, c, a), marks, showDone = true)
        assertEquals(listOf("d", "b", "c", "a"), shown.map { it.entry.sessionId })
        assertEquals(listOf(false, true, false, false), shown.map { it.done })
    }

    @Test fun aDoneSessionWithNewerActivityShowsAgainAndLosesItsMark() {
        val marks = SessionMarks(done = mapOf("b" to 250L, "c" to 200L))
        val rows = HistoryView.arrange(listOf(d, b, c, a), marks, showDone = false)
        assertEquals(listOf("d", "b", "a"), rows.map { it.entry.sessionId })
        assertFalse(rows.first { it.entry.sessionId == "b" }.done)
        assertEquals(setOf("b"), HistoryView.staleDone(listOf(d, b, c, a), marks))
        assertTrue(HistoryView.staleDone(listOf(a), SessionMarks(done = mapOf("zzz" to 1L))).isEmpty())
    }

    @Test fun reconcileKeepsEveryMarkWhenNothingWasScanned() {
        val marks = SessionMarks(pinned = setOf("a"), names = mapOf("b" to "x"), done = mapOf("c" to 1L))
        assertEquals(marks, HistoryView.reconcile(emptyList(), marks, emptySet(), scanned = false, examined = marks.ids()))
    }

    @Test fun reconcilePrunesNothingWhenTheScanIsIncomplete() {
        // projects/ could not be listed: an empty result says nothing about the marks
        val marks = SessionMarks(pinned = setOf("a"), names = mapOf("b" to "x"), done = mapOf("c" to 1L))
        assertEquals(marks, HistoryView.reconcile(emptyList(), marks, emptySet(), scanned = false, examined = marks.ids()))
    }

    @Test fun reconcileWithACompleteEmptyScanStillPrunesNothingWhileMarksAreKeptAlive() {
        // the scan listed nothing, but the on-disk listing that keeps marks alive came back empty too: unknown, keep all
        val marks = SessionMarks(pinned = setOf("a"), names = mapOf("b" to "x"), done = mapOf("c" to 1L))
        val missing = marks.ids()
        assertEquals(missing, HistoryView.keptAlive(missing, onDisk = emptySet()))
        assertEquals(missing, HistoryView.keptAlive(missing, onDisk = null))
        assertEquals(setOf("b"), HistoryView.keptAlive(missing, onDisk = setOf("b", "zzz")))
        assertEquals(emptySet<String>(), HistoryView.keptAlive(emptySet(), onDisk = emptySet()))
        val keep = HistoryView.keptAlive(missing, onDisk = emptySet())
        assertEquals(marks, HistoryView.reconcile(emptyList(), marks, keep, scanned = true, examined = marks.ids()))
    }

    @Test fun reconcileKeepsAMarkThatAppearedAfterTheScanSampledTheStore() {
        // "late" was named after keptAlive sampled the marks: it is in neither the entries nor the keep set, and stays
        val sampled = SessionMarks(pinned = setOf("a", "gone"))
        val now = sampled.rename("late", "Fix login")
        val out = HistoryView.reconcile(listOf(a), now, emptySet(), scanned = true, examined = sampled.ids())
        assertEquals(setOf("a"), out.pinned)
        assertEquals(mapOf("late" to "Fix login"), out.names)
    }

    @Test fun reconcileKeepsMarksOfSessionsKeptAlive() {
        val marks = SessionMarks(pinned = setOf("a", "gone", "alive"))
        val out = HistoryView.reconcile(listOf(a), marks, setOf("alive"), scanned = true, examined = marks.ids())
        assertEquals(setOf("a", "alive"), out.pinned)
    }

    @Test fun emptyTextSaysWhetherTheSessionsAreGoneOrJustDone() {
        assertEquals("No past sessions in this project", HistoryText.empty(scannedCount = 0, shownCount = 0))
        assertEquals("All past sessions are marked done", HistoryText.empty(scannedCount = 3, shownCount = 0))
    }

    /** The eye in the History header says which way it is switched, by its look and its tooltip, not by its background alone. */
    @Test fun theDoneEyeNamesItsState() {
        assertEquals(DoneEye.Presentation(DoneEye.Look.SHOWN, "Showing done sessions"), DoneEye.of(showDone = true))
        assertEquals(DoneEye.Presentation(DoneEye.Look.HIDDEN, "Done sessions hidden"), DoneEye.of(showDone = false))
    }

    @Test fun reconcileFollowsClearsStaleDoneAndPrunes() {
        val earlier = java.nio.file.Paths.get("/t/old.jsonl")
        val newest = d.copy(earlierParts = listOf(earlier))
        val marks = SessionMarks(
            pinned = setOf("old", "gone"),
            names = mapOf("old" to "keep me"),
            done = mapOf("b" to 250L, "c" to 200L),
        )
        val out = HistoryView.reconcile(listOf(newest, b, c), marks, emptySet(), scanned = true, examined = marks.ids())
        assertEquals(setOf("d"), out.pinned)
        assertEquals(mapOf("d" to "keep me"), out.names)
        assertEquals(mapOf("c" to 200L), out.done)
    }
}
