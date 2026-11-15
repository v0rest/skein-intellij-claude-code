package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.Fixture
import io.github.v0rest.skein.testutil.sessionFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ThreadMapperTest {
    /** A folder portable across OS: `/p` on Unix, `C:\p` on Windows, where a bare `/p` is no absolute path. */
    private fun r(relative: String) = Fixture.root(relative)

    private val t0: Instant = Instant.ofEpochMilli(1_000)
    private val probe = FakeProcessProbe(current = 1)
    private val mapper = ThreadMapper(probe)

    // IDE 1 → shell 10 → claude 100 → bash 110 → claude 300 (a nested `claude -p`); iTerm 2 → claude 200
    private val s100 = sessionFile(pid = 100, activity = ClaudeActivity.IDLE, cwd = r("p"), startedAt = 1_500, sessionId = "abc")
    private val s300 = sessionFile(pid = 300, activity = ClaudeActivity.BUSY, cwd = r("p"), startedAt = 1_600, kind = "print")
    private val s200 = sessionFile(pid = 200, activity = ClaudeActivity.IDLE, cwd = r("p"), startedAt = 1_500, sessionId = "zzz")

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
        val s101 = sessionFile(pid = 101, cwd = r("p"), startedAt = 9_500, sessionId = "def")
        assertEquals(101L, mapper.processFor(10, listOf(s101))?.pid)
    }

    @Test
    fun processForIgnoresOtherAnchors() = assertNull(mapper.processFor(10, listOf(s200)))

    @Test
    fun processForPrefersTheEarliestStartedOfTwoOutermost() {
        probe.add(102, parent = 10, start = t0.plusSeconds(5))
        val s102 = sessionFile(pid = 102, cwd = r("p"), startedAt = 5_000)
        assertEquals(100L, mapper.processFor(10, listOf(s102, s100))?.pid)
    }

    private fun pending(resume: String? = null) = PendingLaunch(threadId = "t1", folder = r("p"), typedAt = 1_000, resumeSessionId = resume, preexistingAnchors = emptySet())

    @Test
    fun aShellThatExistedBeforeTheTabIsNotItsShell() {
        // a plain IDE terminal opened before the Skein tab, where the user runs claude by hand
        probe.add(20, parent = 1, start = Instant.ofEpochMilli(-60_000))
        probe.add(120, parent = 20, start = Instant.ofEpochMilli(1_400))
        val byHand = sessionFile(pid = 120, cwd = r("p"), startedAt = 1_500, sessionId = "abc")
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
        assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending().copy(folder = r("p/./sub/..")), listOf(s100), emptySet()))

    @Test
    fun ignoresOtherFolder() = assertNull(mapper.matchLaunch(pending(), listOf(s100.copy(cwd = r("q"))), emptySet()))

    /** A thread New Thread Here started in a subfolder takes the claude that runs there, not one in the project folder. */
    @Test
    fun aThreadInASubfolderTakesTheClaudeThere() {
        probe.add(30, parent = 1, start = Instant.ofEpochMilli(0))
        probe.add(130, parent = 30, start = Instant.ofEpochMilli(1_400))
        val inDocs = sessionFile(pid = 130, cwd = r("p/docs"), startedAt = 1_500, sessionId = "def")
        assertEquals(LaunchMatch(30, inDocs), mapper.matchLaunch(pending().copy(folder = r("p/docs")), listOf(s100, inDocs), emptySet()))
        assertEquals(LaunchMatch(10, s100), mapper.matchLaunch(pending(), listOf(inDocs, s100), emptySet()))
        assertNull(mapper.matchLaunch(pending().copy(folder = r("p/docs")), listOf(s100), emptySet()))
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
        assertEquals(LaunchMatch(10, s100.copy(cwd = r("elsewhere"))), mapper.matchLaunch(pending(resume = "abc"), listOf(s100.copy(cwd = r("elsewhere"))), emptySet()))

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
        val inWorktree = sessionFile(pid = 130, cwd = r("p/.claude/worktrees/feat-x"), startedAt = 1_500, sessionId = "abc")
        assertNull(mapper.matchLaunch(pending(), listOf(inWorktree), emptySet()))
        assertEquals(30L, mapper.matchLaunch(pending().copy(worktreeCwd = r("p/.claude/worktrees/feat-x")), listOf(inWorktree), emptySet())?.anchor)
    }

    @Test
    fun matchIgnoresAnotherWorktree() {
        probe.add(30, parent = 1, start = Instant.ofEpochMilli(0))
        probe.add(130, parent = 30, start = Instant.ofEpochMilli(1_400))
        val other = sessionFile(pid = 130, cwd = r("p/.claude/worktrees/other"), startedAt = 1_500, sessionId = "abc")
        assertNull(mapper.matchLaunch(pending().copy(worktreeCwd = r("p/.claude/worktrees/feat-x")), listOf(other), emptySet()))
    }

    /** A folder that exists on no machine, so the VM judges the text and the disk is never asked. */
    private val winBase = "C:\\w\\0c3b7a1e-5d2f-4e8a-9b61-7f4d2a9c1e55"
    private val winIde = winBase.replace('\\', '/')

    private val windows = ThreadMapper(probe, PathStyle.WINDOWS)

    private fun windowsPending(folder: String, worktree: String? = null) =
        PendingLaunch(threadId = "t1", folder = folder, typedAt = 1_000, resumeSessionId = null, preexistingAnchors = emptySet(), worktreeCwd = worktree)

    /** The IDE says `C:/Users/skein/demo-shop`, claude's status file says `C:\Users\skein\demo-shop`: one folder. */
    @Test
    fun aWindowsFolderMatchesAcrossSeparatorsAndCase() {
        val running = sessionFile(pid = 100, cwd = "$winBase\\demo-shop", startedAt = 1_500, sessionId = "abc")
        for (folder in listOf("$winIde/demo-shop", "$winBase\\demo-shop".lowercase(), "$winIde/demo-shop/")) {
            assertEquals(folder, LaunchMatch(10, running), windows.matchLaunch(windowsPending(folder), listOf(running), emptySet()))
        }
    }

    @Test
    fun aWindowsSiblingOrParentDoesNotMatch() {
        val running = sessionFile(pid = 100, cwd = "$winBase\\demo-shop", startedAt = 1_500, sessionId = "abc")
        for (folder in listOf("$winIde/demo-shop2", "$winIde", "$winIde/demo-shop/web", "D" + "$winIde/demo-shop".drop(1))) {
            assertNull(folder, windows.matchLaunch(windowsPending(folder), listOf(running), emptySet()))
        }
    }

    @Test
    fun aWindowsWorktreeDirectoryCountsAsTheThreadsFolder() {
        val inWorktree = sessionFile(pid = 100, cwd = "$winBase\\demo-shop\\.claude\\worktrees\\task-1", startedAt = 1_500, sessionId = "abc")
        val pending = windowsPending("$winIde/demo-shop", worktree = "$winIde/demo-shop/.claude/worktrees/task-1")
        assertEquals(10L, windows.matchLaunch(pending, listOf(inWorktree), emptySet())?.anchor)
    }

    /**
     * The Claude Code CLIs under a shell, by their executable, each with its start, outermost only: how a claude that has
     * written no status file yet (it asks whether to trust its folder) is told apart from other programs in the tab.
     */
    @Test
    fun theClaudeClisUnderAShellAreTheOutermostOnes() {
        val cli = r("w/bin/claude")
        // IDE 1 → shell 20 → claude 400 → claude 410; shell 20 → vim 420 editing a file named claude; shell 20 → sh 430 → claude 440
        probe.add(20, parent = 1, start = t0)
        probe.add(400, parent = 20, start = t0.plusSeconds(1), command = listOf(cli))
        probe.add(410, parent = 400, start = t0.plusSeconds(2), command = listOf(cli))
        probe.add(420, parent = 20, start = t0.plusSeconds(3), command = listOf(r("usr/bin/vim"), "claude"))
        probe.add(430, parent = 20, start = t0.plusSeconds(4), command = listOf(r("bin/sh")))
        probe.add(440, parent = 430, start = t0.plusSeconds(5), command = listOf(cli, "--worktree", "task-1"))
        assertEquals(listOf(ProcessId(400, t0.plusSeconds(1)), ProcessId(440, t0.plusSeconds(5))), mapper.clisUnder(20).sortedBy { it.pid })
        // claude 100 under shell 10 has no command the system gave, and a pid that is gone has nothing under it
        assertEquals(emptyList<ProcessId>(), mapper.clisUnder(10))
        assertEquals(emptyList<ProcessId>(), mapper.clisUnder(999))
        // a claude whose start the system does not report cannot be told from a later process with its pid
        probe.unknownStart += 400
        assertEquals(listOf(ProcessId(440, t0.plusSeconds(5))), mapper.clisUnder(20))
    }

    /**
     * On Windows the CLI is claude.exe (or a file under claude\versions) under powershell.exe, as the VM showed. Claude
     * Desktop's claude.exe in its WindowsApps package is not, and node.exe, which Windows reports without its script,
     * proves nothing.
     */
    @Test
    fun theClaudeClisUnderAShellOnWindows() {
        val windows = ThreadMapper(probe, PathStyle.WINDOWS)
        probe.add(20, parent = 1, start = t0, command = listOf("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
        probe.add(400, parent = 20, start = t0.plusSeconds(1), command = listOf("C:\\Users\\skein\\.local\\bin\\claude.exe"))
        probe.add(410, parent = 20, start = t0.plusSeconds(2), command = listOf("C:\\Program Files\\WindowsApps\\Claude_1.0.0.0_arm64__abc\\app\\claude.exe"))
        probe.add(420, parent = 20, start = t0.plusSeconds(3), command = listOf("C:\\Program Files\\nodejs\\node.exe"))
        probe.add(430, parent = 20, start = t0.plusSeconds(4), command = listOf("C:\\Users\\skein\\.local\\share\\claude\\versions\\2.1.325"))
        assertEquals(listOf(400L, 430L), windows.clisUnder(20).map { it.pid }.sorted())
    }

    /**
     * What an IDE child is by its file name: on Windows without `.exe` and in any case, on Unix exactly, a "jcef Helper"
     * in any case (T1', T1'').
     */
    @Test
    fun anIdeChildIsToldByItsExecutablesName() {
        fun kind(executable: String?, style: PathStyle) = ThreadMapper.kindOfExecutable(executable, style)
        val windows = mapOf(
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" to IdeChild.SHELL,
            "C:\\Program Files\\PowerShell\\7\\PWSH.EXE" to IdeChild.SHELL,
            "C:\\Windows\\System32\\cmd.exe" to IdeChild.SHELL,
            "C:\\Program Files\\Git\\bin\\bash.exe" to IdeChild.SHELL,
            "C:/tools/nu.exe" to IdeChild.SHELL,
            "C:\\tools\\elvish" to IdeChild.SHELL,
            "C:\\Windows\\System32\\conhost.exe" to IdeChild.HELPER,
            "C:\\Windows\\System32\\OpenConsole.exe" to IdeChild.HELPER,
            "C:\\w\\ide\\bin\\fsnotifier.exe" to IdeChild.HELPER,
            "C:\\w\\ide\\bin\\FSNOTIFIER64.EXE" to IdeChild.HELPER,
            "C:\\w\\ide\\jbr\\lib\\cef_server.exe" to IdeChild.HELPER,
            "C:\\Windows\\System32\\wsl.exe" to IdeChild.UNRECOGNISED,
            "C:\\Users\\skein\\.local\\bin\\claude.exe" to IdeChild.UNRECOGNISED,
            "C:\\tools\\bash.exe.bak" to IdeChild.UNRECOGNISED,
            "C:\\tools\\oddshell.exe" to IdeChild.UNRECOGNISED,
            "" to IdeChild.UNRECOGNISED,
            "   " to IdeChild.UNRECOGNISED,
        )
        for ((executable, expected) in windows) assertEquals(executable, expected, kind(executable, PathStyle.WINDOWS))
        assertEquals(IdeChild.UNRECOGNISED, kind(null, PathStyle.WINDOWS))
        val unix = mapOf(
            "/bin/zsh" to IdeChild.SHELL,
            "/bin/bash" to IdeChild.SHELL,
            "/usr/local/bin/fish" to IdeChild.SHELL,
            "/bin/sh" to IdeChild.SHELL,
            "/opt/homebrew/bin/xonsh" to IdeChild.SHELL,
            "/opt/ide/bin/fsnotifier" to IdeChild.HELPER,
            "/opt/ide/jbr/Contents/Frameworks/jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)" to IdeChild.HELPER,
            "/opt/ide/jbr/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper" to IdeChild.HELPER,
            "/bin/ZSH" to IdeChild.UNRECOGNISED,
            "/bin/zsh.exe" to IdeChild.UNRECOGNISED,
            "/opt/ide/bin/Fsnotifier" to IdeChild.UNRECOGNISED,
            "/usr/bin/tmux" to IdeChild.UNRECOGNISED,
            "/usr/bin/login" to IdeChild.UNRECOGNISED,
            "zsh-helper" to IdeChild.UNRECOGNISED,
        )
        for ((executable, expected) in unix) assertEquals(executable, expected, kind(executable, PathStyle.UNIX))
    }

    @Test
    fun aProcessWithoutACommandIsUnrecognised() {
        probe.add(20, parent = 1, start = t0, command = listOf(r("bin/zsh")))
        probe.add(21, parent = 1, start = t0)
        assertEquals(IdeChild.SHELL, mapper.kindOf(20))
        assertEquals(IdeChild.UNRECOGNISED, mapper.kindOf(21))
        assertEquals(IdeChild.UNRECOGNISED, mapper.kindOf(999))
    }
}
