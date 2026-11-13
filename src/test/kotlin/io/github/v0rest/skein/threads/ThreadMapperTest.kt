package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.sessionFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ThreadMapperTest {
    private val t0: Instant = Instant.ofEpochMilli(1_000)
    private val probe = FakeProcessProbe(current = 1)
    private val mapper = ThreadMapper(probe)

    // IDE 1 → shell 10 → claude 100 → bash 110 → claude 300 (a nested `claude -p`); iTerm 2 → claude 200
    private val s100 = sessionFile(pid = 100, activity = ClaudeActivity.IDLE, cwd = "/p", startedAt = 1_500, sessionId = "abc")
    private val s300 = sessionFile(pid = 300, activity = ClaudeActivity.BUSY, cwd = "/p", startedAt = 1_600, kind = "print")
    private val s200 = sessionFile(pid = 200, activity = ClaudeActivity.IDLE, cwd = "/p", startedAt = 1_500, sessionId = "zzz")

    @Before
    fun tree() {
        probe.add(1, parent = null, start = t0)
        probe.add(10, parent = 1, start = t0)
        probe.add(100, parent = 10, start = t0.plusSeconds(1))
        probe.add(110, parent = 100, start = t0.plusSeconds(2))
        probe.add(300, parent = 110, start = t0.plusSeconds(3))
        probe.add(2, parent = null, start = t0)
        probe.add(200, parent = 2, start = t0.plusSeconds(1))
    }

    @Test
    fun anchorOfClaudeIsShell() = assertEquals(10L, mapper.anchorOf(100))

    @Test
    fun anchorOfNestedProcessIsStillTheShell() = assertEquals(10L, mapper.anchorOf(300))

    @Test
    fun anchorOfDirectChildIsItself() = assertEquals(10L, mapper.anchorOf(10))

    @Test
    fun foreignProcessHasNoAnchor() {
        assertNull(mapper.anchorOf(200))
        assertNull(mapper.anchorOf(1))
        assertNull(mapper.anchorOf(999))
    }

    @Test
    fun anchorOfGivesUpOnCycles() {
        probe.add(50, parent = 51, start = t0)
        probe.add(51, parent = 50, start = t0)
        assertNull(mapper.anchorOf(50))
    }

    @Test
    fun nestedClaudeUnderThreadIsIgnored() = assertEquals(100L, mapper.processFor(10, listOf(s100, s300))?.pid)

    @Test
    fun nestedNonInteractiveClaudeAloneIsNotTheThread() = assertNull(mapper.processFor(10, listOf(s300)))

    @Test
    fun relaunchIsFollowed() {
        probe.kill(100)
        probe.add(101, parent = 10, start = t0.plusSeconds(9))
        val s101 = sessionFile(pid = 101, cwd = "/p", startedAt = 9_500, sessionId = "def")
        assertEquals(101L, mapper.processFor(10, listOf(s101))?.pid)
    }

    @Test
    fun processForIgnoresOtherAnchors() = assertNull(mapper.processFor(10, listOf(s200)))

    @Test
    fun processForPrefersTheEarliestStartedOfTwoOutermost() {
        probe.add(102, parent = 10, start = t0.plusSeconds(5))
        val s102 = sessionFile(pid = 102, cwd = "/p", startedAt = 5_000)
        assertEquals(100L, mapper.processFor(10, listOf(s102, s100))?.pid)
    }

    private fun pending(resume: String? = null) = PendingLaunch(threadId = "t1", folder = "/p", typedAt = 1_000, resumeSessionId = resume, preexistingAnchors = emptySet())

    @Test
    fun aShellThatExistedBeforeTheTabIsNotItsShell() {
        // a plain IDE terminal opened before the Skein tab, where the user runs claude by hand
        probe.add(20, parent = 1, start = Instant.ofEpochMilli(-60_000))
        probe.add(120, parent = 20, start = Instant.ofEpochMilli(1_400))
        val byHand = sessionFile(pid = 120, cwd = "/p", startedAt = 1_500, sessionId = "abc")
        assertNull(mapper.matchLaunch(pending().copy(preexistingAnchors = setOf(20)), listOf(byHand), emptySet()))
        assertNull(mapper.matchLaunch(pending(resume = "abc").copy(preexistingAnchors = setOf(20)), listOf(byHand), emptySet()))
        assertEquals("without that knowledge it would match", 20L, mapper.matchLaunch(pending(), listOf(byHand), emptySet())?.anchor)
    }

    @Test
    fun ideChildrenAreTheCandidateShells() = assertEquals(setOf(10L), mapper.ideChildren())

    @Test
    fun matchByFolderAndTime() = assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending(), listOf(s100), emptySet()))

    @Test
    fun matchNormalisesTheFolder() =
        assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending().copy(folder = "/p/./sub/.."), listOf(s100), emptySet()))

    @Test
    fun ignoresOtherFolder() = assertNull(mapper.matchLaunch(pending(), listOf(s100.copy(cwd = "/q")), emptySet()))

    /** A thread New Thread Here started in a subfolder takes the claude that runs there, not one in the project folder. */
    @Test
    fun aThreadInASubfolderTakesTheClaudeThere() {
        probe.add(30, parent = 1, start = Instant.ofEpochMilli(0))
        probe.add(130, parent = 30, start = Instant.ofEpochMilli(1_400))
        val inDocs = sessionFile(pid = 130, cwd = "/p/docs", startedAt = 1_500, sessionId = "def")
        assertEquals(LaunchMatch(30, inDocs), mapper.matchLaunch(pending().copy(folder = "/p/docs"), listOf(s100, inDocs), emptySet()))
        assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending(), listOf(inDocs, s100), emptySet()))
        assertNull(mapper.matchLaunch(pending().copy(folder = "/p/docs"), listOf(s100), emptySet()))
    }

    @Test
    fun ignoresSessionStartedBeforeTyping() {
        assertNull(mapper.matchLaunch(pending(), listOf(s100.copy(startedAt = 999)), emptySet()))
        assertEquals(LaunchMatch(10, s100.copy(startedAt = 1_000)), mapper.matchLaunch(pending(), listOf(s100.copy(startedAt = 1_000)), emptySet()))
    }

    @Test
    fun ignoresSessionWithoutStartTime() = assertNull(mapper.matchLaunch(pending(), listOf(s100.copy(startedAt = null)), emptySet()))

    @Test
    fun ignoresForeignTerminal() = assertNull(mapper.matchLaunch(pending(), listOf(s200), emptySet()))

    @Test
    fun ignoresOwnedAnchor() = assertNull(mapper.matchLaunch(pending(), listOf(s100), setOf(10L)))

    @Test
    fun ignoresNestedAndNonInteractiveSessions() =
        assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending(), listOf(s300, s100), emptySet()))

    @Test
    fun resumeMatchesBySessionIdEvenInAnotherFolder() =
        assertEquals(LaunchMatch(10, s100.copy(cwd = "/elsewhere")), mapper.matchLaunch(pending(resume = "abc"), listOf(s100.copy(cwd = "/elsewhere")), emptySet()))

    @Test
    fun resumeDoesNotAdoptAnOlderProcessWithTheSameId() {
        // the same session already running in a plain IDE terminal tab, started before Skein typed
        assertNull(mapper.matchLaunch(pending(resume = "abc"), listOf(s100.copy(startedAt = 900)), emptySet()))
    }

    @Test
    fun nestedInteractiveClaudeThatStartedEarlierStillLosesToTheOuterOne() {
        // a nested session that passes the kind filter and even sorts first must still not be the thread
        val nested = s300.copy(kind = "interactive", startedAt = 1_100)
        assertEquals(100L, mapper.processFor(10, listOf(s100, nested))?.pid)
        assertEquals(100L, mapper.processFor(10, listOf(s100.copy(startedAt = null), nested))?.pid)
        assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending(), listOf(nested, s100), emptySet()))
    }

    @Test
    fun folderMatchSeesThroughSymlinks() {
        val real = java.nio.file.Files.createTempDirectory("skein-real")
        val link = real.resolveSibling(real.fileName.toString() + "-link")
        try {
            java.nio.file.Files.createSymbolicLink(link, real)
            val file = s100.copy(cwd = real.toRealPath().toString())
            assertEquals(LaunchMatch(10, file), mapper.matchLaunch(pending().copy(folder = link.toString()), listOf(file), emptySet()))
        } finally {
            java.nio.file.Files.deleteIfExists(link)
            java.nio.file.Files.deleteIfExists(real)
        }
    }

    @Test
    fun resumeFallsBackToFolderAndTimeWhenTheIdDiffers() =
        assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending(resume = "not-this-one"), listOf(s100), emptySet()))

    @Test
    fun resumeNeverMatchesAForeignTerminalById() = assertNull(mapper.matchLaunch(pending(resume = "zzz"), listOf(s200), emptySet()))

    @Test
    fun matchAcceptsTheExpectedWorktreeCwd() {
        probe.add(30, parent = 1, start = Instant.ofEpochMilli(0))
        probe.add(130, parent = 30, start = Instant.ofEpochMilli(1_400))
        val inWorktree = sessionFile(pid = 130, cwd = "/p/.claude/worktrees/feat-x", startedAt = 1_500, sessionId = "abc")
        assertNull(mapper.matchLaunch(pending(), listOf(inWorktree), emptySet()))
        assertEquals(30L, mapper.matchLaunch(pending().copy(worktreeCwd = "/p/.claude/worktrees/feat-x"), listOf(inWorktree), emptySet())?.anchor)
    }

    @Test
    fun matchIgnoresAnotherWorktree() {
        probe.add(30, parent = 1, start = Instant.ofEpochMilli(0))
        probe.add(130, parent = 30, start = Instant.ofEpochMilli(1_400))
        val other = sessionFile(pid = 130, cwd = "/p/.claude/worktrees/other", startedAt = 1_500, sessionId = "abc")
        assertNull(mapper.matchLaunch(pending().copy(worktreeCwd = "/p/.claude/worktrees/feat-x"), listOf(other), emptySet()))
    }
}
