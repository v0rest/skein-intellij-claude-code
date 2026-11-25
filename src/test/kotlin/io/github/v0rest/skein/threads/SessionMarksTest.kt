package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMarksTest {
    private val a = "55555555-5555-4555-8555-555555555555"
    private val b = "66666666-6666-4666-8666-666666666666"
    private val c = "77777777-7777-4777-8777-777777777777"

    @Test fun renameSetsAndABlankNameClears() {
        val named = SessionMarks().rename(a, "Fix login")
        assertEquals(mapOf(a to "Fix login"), named.names)
        assertEquals(emptyMap<String, String>(), named.rename(a, "  ").names)
        assertEquals(emptyMap<String, String>(), named.rename(a, null).names)
    }

    @Test fun markDoneRemembersWhenAndUndoneForgets() {
        val done = SessionMarks().markDone(listOf(a, b), 42L)
        assertEquals(mapOf(a to 42L, b to 42L), done.done)
        assertEquals(mapOf(b to 42L), done.undone(listOf(a)).done)
    }

    @Test fun pinAddsAndRemoves() {
        val pinned = SessionMarks().pin(listOf(a, b), true)
        assertEquals(setOf(a, b), pinned.pinned)
        assertEquals(setOf(b), pinned.pin(listOf(a), false).pinned)
    }

    @Test fun followMovesMarksToTheSessionThatNowStandsForTheConversation() {
        val marks = SessionMarks(pinned = setOf(a), names = mapOf(a to "Old", b to "Own"), done = mapOf(a to 7L))
        val followed = marks.follow(b, listOf(a))
        assertEquals(setOf(b), followed.pinned)
        assertEquals(mapOf(b to "Own"), followed.names)
        assertEquals(mapOf(b to 7L), followed.done)
    }

    @Test fun pruneDropsMarksOfSessionsNoLongerFound() {
        val marks = SessionMarks(setOf(a, b), mapOf(a to "A", b to "B"), mapOf(a to 1L, b to 2L))
        assertEquals(SessionMarks(setOf(a), mapOf(a to "A"), mapOf(a to 1L)), marks.prune(setOf(a), emptySet(), examined = marks.ids()))
    }

    @Test fun pruneLeavesAloneTheMarksItDidNotExamine() {
        // a mark added after the scan sampled the store is not pruned until a scan has looked at it
        val marks = SessionMarks(setOf(a, b), mapOf(c to "C"), mapOf(b to 2L))
        val pruned = marks.prune(emptySet(), emptySet(), examined = setOf(a, b))
        assertEquals(SessionMarks(emptySet(), mapOf(c to "C"), emptyMap()), pruned)
        assertEquals(setOf(a, b, c), marks.ids())
    }

    @Test fun pruneKeepsMarksOfSessionsThreadsHold() {
        val marks = SessionMarks(setOf(a, b), mapOf(b to "B"), mapOf(c to 3L))
        val pruned = marks.prune(emptySet(), setOf(b, c), examined = marks.ids())
        assertEquals(setOf(b), pruned.pinned)
        assertEquals(mapOf(b to "B"), pruned.names)
        assertEquals(mapOf(c to 3L), pruned.done)
        assertTrue(marks.prune(emptySet(), emptySet(), examined = marks.ids()) == SessionMarks())
    }

    // a rename that should also reach Claude Code waits as a pending name until Skein types it into the session's claude

    @Test fun aRenameForClaudeCodeKeepsTheNameWaitingBesideTheSkeinName() {
        val marks = SessionMarks().rename(a, " task-1 ", inClaude = true)
        assertEquals(mapOf(a to "task-1"), marks.names)
        assertEquals(mapOf(a to "task-1"), marks.claudeRenames)
        assertEquals(emptyMap<String, String>(), SessionMarks().rename(a, "task-1").claudeRenames)
    }

    @Test fun aNewerRenameDecidesWhatWaits() {
        val waiting = SessionMarks().rename(a, "task-1", inClaude = true)
        assertEquals(mapOf(a to "demo-shop"), waiting.rename(a, "demo-shop", inClaude = true).claudeRenames)
        // the newer rename did not tick the box, so the older wish goes
        val skeinOnly = waiting.rename(a, "demo-shop", inClaude = false)
        assertEquals(mapOf(a to "demo-shop"), skeinOnly.names)
        assertEquals(emptyMap<String, String>(), skeinOnly.claudeRenames)
    }

    @Test fun clearingTheSkeinNameDropsWhatWaits() {
        val waiting = SessionMarks().rename(a, "task-1", inClaude = true).rename(b, "demo-shop", inClaude = true)
        val cleared = waiting.rename(a, null)
        assertEquals(mapOf(b to "demo-shop"), cleared.names)
        assertEquals(mapOf(b to "demo-shop"), cleared.claudeRenames)
        assertEquals(emptyMap<String, String>(), waiting.rename(a, "  ", inClaude = true).rename(b, "").claudeRenames)
    }

    @Test fun aSentNameStopsWaitingUnlessANewerOneCameMeanwhile() {
        val waiting = SessionMarks().rename(a, "task-1", inClaude = true)
        val sent = waiting.claudeRenameSent(a, "task-1")
        assertEquals(emptyMap<String, String>(), sent.claudeRenames)
        assertEquals("the Skein name stays until Claude Code shows it", mapOf(a to "task-1"), sent.names)
        val newer = waiting.rename(a, "demo-shop", inClaude = true)
        assertEquals(newer, newer.claudeRenameSent(a, "task-1"))
    }

    @Test fun aRenameWaitsOnlyAsTheNameItIs() {
        val waiting = SessionMarks().rename(a, "task-1", inClaude = true)
        assertTrue(waiting.waitsFor(a, "task-1"))
        assertEquals(false, waiting.waitsFor(a, "demo-shop"))
        assertEquals(false, waiting.waitsFor(b, "task-1"))
        assertEquals("replaced", false, waiting.rename(a, "demo-shop", inClaude = true).waitsFor(a, "task-1"))
        assertEquals("dropped", false, waiting.rename(a, "task-1").waitsFor(a, "task-1"))
    }

    /** Once Claude Code shows the name itself, the Skein name goes, so a later `/rename` in claude is followed again. */
    @Test fun theConfirmedNameLeavesTheSkeinName() {
        val sent = SessionMarks().rename(a, "task-1", inClaude = true).claudeRenameSent(a, "task-1")
        assertEquals(emptyMap<String, String>(), sent.claudeNameConfirmed(a, "task-1").names)
        // the user renamed it again meanwhile: that newer name is theirs and stays
        val renamed = sent.rename(a, "demo-shop")
        assertEquals(renamed, renamed.claudeNameConfirmed(a, "task-1"))
        val waitingAgain = sent.rename(a, "task-1", inClaude = true)
        assertEquals(waitingAgain, waitingAgain.claudeNameConfirmed(a, "task-1"))
    }

    @Test fun followCarriesWhatWaitsWithTheName() {
        val marks = SessionMarks(names = mapOf(a to "task-1"), claudeRenames = mapOf(a to "task-1"))
        val followed = marks.follow(b, listOf(a))
        assertEquals(mapOf(b to "task-1"), followed.names)
        assertEquals(mapOf(b to "task-1"), followed.claudeRenames)
        // the session that stands for the conversation now has a Skein name of its own: that one counts, nothing waits
        val own = SessionMarks(names = mapOf(a to "task-1", b to "Own"), claudeRenames = mapOf(a to "task-1")).follow(b, listOf(a))
        assertEquals(mapOf(b to "Own"), own.names)
        assertEquals(emptyMap<String, String>(), own.claudeRenames)
    }

    @Test fun pruneAndIdsCountWhatWaits() {
        val marks = SessionMarks(names = mapOf(a to "A", b to "B"), claudeRenames = mapOf(a to "A", b to "B"))
        assertEquals(setOf(a, b), marks.ids())
        val pruned = marks.prune(setOf(a), emptySet(), examined = marks.ids())
        assertEquals(mapOf(a to "A"), pruned.claudeRenames)
        assertEquals(mapOf(b to "B"), marks.prune(emptySet(), setOf(b), examined = marks.ids()).claudeRenames)
    }

    /** A thread whose session changed (`/clear`, a resume under a new id) takes its name along, and what waits with it. */
    @Test fun carryMovesNamesAndWhatWaitsAlongWithTheThread() {
        val marks = SessionMarks(names = mapOf(a to "task-1"), claudeRenames = mapOf(a to "task-1"))
        val carried = marks.carry(mapOf("t1" to a), mapOf("t1" to b))
        assertEquals(mapOf(a to "task-1", b to "task-1"), carried.names)
        assertEquals(mapOf(a to "task-1", b to "task-1"), carried.claudeRenames)
        // the new session already had a Skein name of its own: neither moves
        val own = SessionMarks(names = mapOf(a to "task-1", b to "Own"), claudeRenames = mapOf(a to "task-1")).carry(mapOf("t1" to a), mapOf("t1" to b))
        assertEquals(mapOf(a to "task-1", b to "Own"), own.names)
        assertEquals(mapOf(a to "task-1"), own.claudeRenames)
        assertEquals(marks, marks.carry(mapOf("t1" to a), mapOf("t1" to a)))
    }

    // a thread's colour is remembered for its session too, so a resume from History after the thread closed brings it back

    @Test fun colorSetsAndNoneClears() {
        val coloured = SessionMarks().color(listOf(a, b), ThreadColor.RED)
        assertEquals(mapOf(a to ThreadColor.RED, b to ThreadColor.RED), coloured.colors)
        assertEquals(mapOf(a to ThreadColor.BLUE, b to ThreadColor.RED), coloured.color(listOf(a), ThreadColor.BLUE).colors)
        assertEquals(mapOf(b to ThreadColor.RED), coloured.color(listOf(a), null).colors)
        assertEquals(coloured, coloured.color(emptyList(), null))
    }

    @Test fun followMovesAColourToTheSessionThatNowStandsForTheConversation() {
        val followed = SessionMarks(colors = mapOf(a to ThreadColor.GREEN)).follow(b, listOf(a))
        assertEquals(mapOf(b to ThreadColor.GREEN), followed.colors)
        // the session that stands for the conversation now has a colour of its own - that one counts
        val own = SessionMarks(colors = mapOf(a to ThreadColor.GREEN, b to ThreadColor.PURPLE)).follow(b, listOf(a))
        assertEquals(mapOf(b to ThreadColor.PURPLE), own.colors)
        // the other marks moving along keep the colours where they are
        val withName = SessionMarks(names = mapOf(a to "task-1"), colors = mapOf(c to ThreadColor.BLUE)).follow(b, listOf(a))
        assertEquals(mapOf(c to ThreadColor.BLUE), withName.colors)
    }

    /** A thread whose session changed (`/clear`, a resume under a new id) takes its colour along, as it does its name. */
    @Test fun carryMovesAColourAlongWithTheThread() {
        val marks = SessionMarks(colors = mapOf(a to ThreadColor.ORANGE))
        assertEquals(mapOf(a to ThreadColor.ORANGE, b to ThreadColor.ORANGE), marks.carry(mapOf("t1" to a), mapOf("t1" to b)).colors)
        // the new session already had a colour of its own - it keeps it
        val own = SessionMarks(colors = mapOf(a to ThreadColor.ORANGE, b to ThreadColor.YELLOW)).carry(mapOf("t1" to a), mapOf("t1" to b))
        assertEquals(mapOf(a to ThreadColor.ORANGE, b to ThreadColor.YELLOW), own.colors)
        // a name moving along keeps the colours too
        val named = SessionMarks(names = mapOf(a to "task-1"), colors = mapOf(c to ThreadColor.BLUE)).carry(mapOf("t1" to a), mapOf("t1" to b))
        assertEquals(mapOf(c to ThreadColor.BLUE), named.colors)
    }

    @Test fun pruneDropsTheColoursOfSessionsGoneAndKeepsTheHeldOnes() {
        val marks = SessionMarks(pinned = setOf(a), colors = mapOf(a to ThreadColor.RED, b to ThreadColor.GREEN, c to ThreadColor.BLUE))
        assertEquals(setOf(a, b, c), marks.ids())
        val pruned = marks.prune(known = setOf(a), held = setOf(b), examined = marks.ids())
        assertEquals(mapOf(a to ThreadColor.RED, b to ThreadColor.GREEN), pruned.colors)
        assertEquals("one not examined stays", mapOf(c to ThreadColor.BLUE), marks.prune(emptySet(), emptySet(), examined = setOf(a, b)).colors)
    }
}
