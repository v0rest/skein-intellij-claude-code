package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Claude Code's own worktree lock: its reason as git shows it ([ClaudeWorktreeLock.parse]), and whether the claude that
 * made it still runs ([WorktreeLock.of]).
 */
class ClaudeWorktreeLockTest {
    private val reason = "claude session demo (pid 9384 start Tue Nov 10 08:30:41 2026)"

    /** The start the reason names, read in UTC. */
    private val written: Instant = Instant.parse("2026-11-10T08:30:41Z")
    private val claude = listOf("/w/bin/claude", "--worktree", "demo")

    @Test
    fun readsClaudeCodesOwnReason() {
        assertEquals(ClaudeWorktreeLock("demo", 9384, "Tue Nov 10 08:30:41 2026"), ClaudeWorktreeLock.parse(reason))
        // `ps` pads a one-digit day with a second space
        assertEquals(
            ClaudeWorktreeLock("task-1", 12, "Tue Nov  3 08:30:41 2026"),
            ClaudeWorktreeLock.parse("claude session task-1 (pid 12 start Tue Nov  3 08:30:41 2026)"),
        )
    }

    @Test
    fun anyOtherReasonIsALockSomeoneElseMade() {
        val others = listOf(
            "",
            "on a stick",
            "claude session demo",
            "session demo (pid 9384 start Tue Nov 10 08:30:41 2026)",
            "my claude session demo (pid 9384 start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 9384 start Tue Nov 10 08:30:41 2026) and more",
            "claude session my demo (pid 9384 start Tue Nov 10 08:30:41 2026)",
            "Claude session demo (pid 9384 start Tue Nov 10 08:30:41 2026)",
            // git C-quotes a reason with a byte it cannot show as it is, so it never reaches Skein in this shape
            "\"claude session demo (pid 9384 start Tue Nov 10 08:30:41 2026) \\303\\251\"",
            "claude session demo (pid 9384 start Tue Nov 10 (08:30:41) 2026)",
        )
        for (other in others) assertNull(other, ClaudeWorktreeLock.parse(other))
        assertNull(ClaudeWorktreeLock.parse(null))
    }

    @Test
    fun oddSpacingIsNotClaudeCodesShape() {
        val odd = listOf(
            " $reason",
            "$reason ",
            "$reason\n",
            "claude  session demo (pid 9384 start Tue Nov 10 08:30:41 2026)",
            "claude session  demo (pid 9384 start Tue Nov 10 08:30:41 2026)",
            "claude session\tdemo (pid 9384 start Tue Nov 10 08:30:41 2026)",
            "claude session demo(pid 9384 start Tue Nov 10 08:30:41 2026)",
            "claude session demo ( pid 9384 start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid  9384 start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 9384  start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 9384 start  Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 9384 start Tue Nov 10 08:30:41 2026 )",
            "claude session demo (pid 9384 start Tue Nov 10\t08:30:41 2026)",
            "claude session demo (pid 9384 start Tue Nov 10\n08:30:41 2026)",
        )
        for (reason in odd) assertNull(reason, ClaudeWorktreeLock.parse(reason))
    }

    @Test
    fun aMissingOrBrokenPidIsNotClaudeCodesShape() {
        val broken = listOf(
            "claude session demo (start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid abc start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 0 start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 09384 start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid -9384 start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 12345678901 start Tue Nov 10 08:30:41 2026)",
            "claude session demo (pid 9384 start )",
            "claude session demo (pid 9384 start)",
            "claude session demo (pid )",
            "claude session demo (pid 09384)",
            "claude session demo (pid 0)",
        )
        for (reason in broken) assertNull(reason, ClaudeWorktreeLock.parse(reason))
    }

    @Test
    fun readsTheStartInTheGivenZoneWithEitherPadding() {
        assertEquals(listOf(written), ClaudeWorktreeLock.startsOf("Tue Nov 10 08:30:41 2026", ZoneOffset.UTC))
        assertEquals(listOf(Instant.parse("2026-11-03T08:30:41Z")), ClaudeWorktreeLock.startsOf("Tue Nov  3 08:30:41 2026", ZoneOffset.UTC))
        assertEquals(listOf(Instant.parse("2026-11-03T08:30:41Z")), ClaudeWorktreeLock.startsOf("Tue Nov 3 08:30:41 2026", ZoneOffset.UTC))
        assertEquals(listOf(Instant.parse("2026-11-10T06:30:41Z")), ClaudeWorktreeLock.startsOf("Tue Nov 10 08:30:41 2026", ZoneId.of("Europe/Kyiv")))
    }

    @Test
    fun aStartThatCannotBeReadGivesNothing() {
        for (text in listOf("someday", "Mon Nov 10 08:30:41 2026", "Tue Nov 10 2026 08:30:41", "Tue Nov 10 08:30:41 CET 2026", "Tue Nov 31 08:30:41 2026")) {
            assertEquals(text, emptyList<Instant>(), ClaudeWorktreeLock.startsOf(text, ZoneOffset.UTC))
        }
    }

    /** The hour the clocks go back happens twice; a start in either one is that start. */
    @Test
    fun anHourThatHappensTwiceGivesBothInstants() {
        assertEquals(
            listOf(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T01:30:00Z")),
            ClaudeWorktreeLock.startsOf("Sun Oct 25 02:30:00 2026", ZoneId.of("Europe/Berlin")),
        )
    }

    /** The rows here were written with macOS and Linux commands, so they are read by those rules on any system. */
    private fun of(
        probe: FakeProcessProbe,
        reason: String? = this.reason,
        locked: Boolean = true,
        zone: ZoneId = ZoneOffset.UTC,
        style: PathStyle = PathStyle.UNIX,
    ) = WorktreeLock.of(locked, reason, probe, zone, style)

    private val stale get() = WorktreeLock.Stale(ClaudeWorktreeLock.parse(reason)!!)
    private val sleep = listOf("/bin/sleep", "60")

    @Test
    fun notLockedIsNone() {
        assertEquals(WorktreeLock.None, of(FakeProcessProbe(), reason = null, locked = false))
    }

    @Test
    fun aLockWithoutAReasonOrWithAnotherReasonIsForeign() {
        val probe = FakeProcessProbe().apply { add(9384, null, written, claude) }
        assertEquals(WorktreeLock.Foreign, of(probe, reason = null))
        assertEquals(WorktreeLock.Foreign, of(probe, reason = "on a stick"))
        assertEquals(WorktreeLock.Foreign, of(probe, reason = " $reason"))
    }

    @Test
    fun aDeadPidMakesTheLockStale() {
        assertEquals(stale, of(FakeProcessProbe()))
    }

    @Test
    fun aLiveClaudeStartedWhenTheReasonSaysHoldsTheLock() {
        for (offset in listOf(0L, 400L, 1_900L, -1_900L)) {
            val probe = FakeProcessProbe().apply { add(9384, null, written.plusMillis(offset), claude) }
            assertEquals("$offset", WorktreeLock.Live(9384), of(probe))
        }
    }

    /** Its command vouches for a claude whose start reads another time: a zone the text was not read in, or a drift. */
    @Test
    fun aClaudeCommandVouchesThoughItsStartIsOff() {
        for (offset in listOf(5_000L, -5_000L, 3_600_000L, -3_600_000L, 3 * 86_400_000L)) {
            val probe = FakeProcessProbe().apply { add(9384, null, written.plusMillis(offset), claude) }
            assertEquals("$offset", WorktreeLock.Live(9384), of(probe))
        }
    }

    /** A start that fits vouches for a process whose command Skein does not know as claude: a wrapper, a script. */
    @Test
    fun aMatchingStartVouchesForACommandSkeinDoesNotKnow() {
        val commands = listOf(sleep, listOf("/w/bin/node", "/w/lib/cli.js", "--worktree", "demo"), listOf("/w/bin/claude-wrapper.sh"))
        for (command in commands) {
            for (offset in listOf(0L, 1_900L, -1_900L)) {
                val probe = FakeProcessProbe().apply { add(9384, null, written.plusMillis(offset), command) }
                assertEquals("$command $offset", WorktreeLock.Live(9384), of(probe))
            }
        }
    }

    /** A live pid is another process only when its command and its start both say so. */
    @Test
    fun aLivePidIsStaleOnlyWhenItsCommandAndItsStartBothSayItIsNotThatClaude() {
        for (offset in listOf(5_000L, -5_000L, 3 * 86_400_000L)) {
            val probe = FakeProcessProbe().apply { add(9384, null, written.plusMillis(offset), sleep) }
            assertEquals("$offset", stale, of(probe))
        }
    }

    @Test
    fun claudeInEveryFormItIsInstalledHoldsTheLock() {
        val forms = listOf(
            listOf("/w/bin/claude"),
            listOf("/w/.local/share/claude/versions/2.1.0", "--worktree", "demo"),
            listOf("/w/bin/node", "/w/bin/claude", "--worktree", "demo"),
        )
        for (command in forms) {
            val probe = FakeProcessProbe().apply { add(9384, null, written.plusSeconds(3_600), command) }
            assertEquals(command.toString(), WorktreeLock.Live(9384), of(probe))
        }
    }

    /** What the system does not say is no proof: a live pid stays the lock's holder unless both signals say otherwise. */
    @Test
    fun whatCannotBeToldKeepsALivePidTheHolder() {
        val unknownCommand = FakeProcessProbe().apply { add(9384, null, written.plusSeconds(60), emptyList()) }
        assertEquals(WorktreeLock.Live(9384), of(unknownCommand))
        val unknownStart = FakeProcessProbe().apply {
            add(9384, null, written.plusSeconds(3_600), sleep)
            unknownStart += 9384L
        }
        assertEquals(WorktreeLock.Live(9384), of(unknownStart))
        val unreadableDate = FakeProcessProbe().apply { add(9384, null, written.plusSeconds(3_600), sleep) }
        assertEquals(WorktreeLock.Live(9384), of(unreadableDate, reason = "claude session demo (pid 9384 start someday)"))
        assertEquals(WorktreeLock.Live(9384), of(unreadableDate, reason = "claude session demo (pid 9384 start Mon Nov 10 08:30:41 2026)"))
    }

    /**
     * Claude Code was seen writing the start in UTC while the system ran an hour ahead, so the text counts in UTC and
     * in the system's zone alike, whichever zone the system has.
     */
    @Test
    fun theStartIsReadInUtcAndInTheSystemZone() {
        val kyiv = ZoneId.of("Europe/Kyiv")
        val writtenInUtc = FakeProcessProbe().apply { add(9384, null, Instant.parse("2026-11-10T08:30:41.300Z"), sleep) }
        assertEquals(WorktreeLock.Live(9384), of(writtenInUtc, zone = kyiv))
        val writtenInKyiv = FakeProcessProbe().apply { add(9384, null, Instant.parse("2026-11-10T06:30:41.300Z"), sleep) }
        assertEquals(WorktreeLock.Live(9384), of(writtenInKyiv, zone = kyiv))
        // in neither zone, and the command is not claude either
        val neither = FakeProcessProbe().apply { add(9384, null, Instant.parse("2026-11-10T07:30:41Z"), sleep) }
        assertEquals(stale, of(neither, zone = kyiv))
        assertEquals(stale, of(writtenInKyiv, zone = ZoneOffset.UTC))
    }

    @Test
    fun aStartInEitherRunOfTheRepeatedHourVouches() {
        val berlin = ZoneId.of("Europe/Berlin")
        val repeated = "claude session demo (pid 9384 start Sun Oct 25 02:30:00 2026)"
        for (start in listOf("2026-10-25T00:30:00.500Z", "2026-10-25T01:30:00.500Z", "2026-10-25T02:30:00.500Z")) {
            val probe = FakeProcessProbe().apply { add(9384, null, Instant.parse(start), sleep) }
            assertEquals(start, WorktreeLock.Live(9384), of(probe, reason = repeated, zone = berlin))
        }
        val other = FakeProcessProbe().apply { add(9384, null, Instant.parse("2026-10-25T03:30:00Z"), sleep) }
        assertEquals(WorktreeLock.Stale(ClaudeWorktreeLock.parse(repeated)!!), of(other, reason = repeated, zone = berlin))
    }

    // on Windows Claude Code writes the reason without a start, as seen on Windows 11

    private val windowsReason = "claude session task-1 (pid 4528)"
    private val windowsStale get() = WorktreeLock.Stale(ClaudeWorktreeLock.parse(windowsReason)!!)
    private val windowsCli = listOf("""C:\Users\acme\.local\bin\claude.exe""")

    private fun windows(command: List<String>?, start: Instant = written) = of(
        FakeProcessProbe().apply { if (command != null) add(4528, null, start, command) },
        reason = windowsReason,
        style = PathStyle.WINDOWS,
    )

    @Test
    fun readsTheReasonWithoutAStart() {
        assertEquals(ClaudeWorktreeLock("task-1", 4528, null), ClaudeWorktreeLock.parse(windowsReason))
        assertEquals(ClaudeWorktreeLock("demo", 9384, null), ClaudeWorktreeLock.parse("claude session demo (pid 9384)"))
    }

    @Test
    fun oddSpacingWithoutAStartIsNotClaudeCodesShape() {
        val odd = listOf(
            " $windowsReason",
            "$windowsReason ",
            "$windowsReason\n",
            "claude session task-1 (pid 4528 )",
            "claude session task-1 (pid  4528)",
            "claude session task-1 ( pid 4528)",
            "claude session task-1(pid 4528)",
            "claude session task-1 (pid\t4528)",
            "claude session task-1 (pid 4528) and more",
            "\"$windowsReason\"",
        )
        for (reason in odd) assertNull(reason, ClaudeWorktreeLock.parse(reason))
    }

    @Test
    fun withoutAStartADeadPidMakesTheLockStale() {
        assertEquals(windowsStale, windows(command = null))
    }

    /** No start to compare, so a live CLI is the holder whenever it started. */
    @Test
    fun withoutAStartALiveCliHoldsTheLock() {
        val forms = listOf(
            windowsCli,
            listOf("""C:\Users\acme\.local\bin\CLAUDE.EXE"""),
            listOf("""C:\Users\acme\.local\share\claude\versions\2.1.325"""),
            listOf("C:/Users/acme/.local/bin/claude.exe"),
        )
        for (command in forms) {
            for (start in listOf(written, written.plusSeconds(3_600), written.minusSeconds(86_400))) {
                assertEquals("$command $start", WorktreeLock.Live(4528), windows(command, start))
            }
        }
    }

    /** What Windows does not tell keeps a live pid the holder: no command, or node, bun or deno, which may be running claude. */
    @Test
    fun withoutAStartAnUnknownCommandHoldsTheLock() {
        val runtimes = listOf("""C:\Program Files\nodejs\node.exe""", """C:\Users\acme\.bun\bin\bun.exe""", """C:\Users\acme\.deno\bin\deno.exe""")
        for (command in listOf(emptyList(), listOf("")) + runtimes.map { listOf(it) }) {
            assertEquals("$command", WorktreeLock.Live(4528), windows(command))
        }
    }

    /** A live pid that is clearly another program now, Claude Desktop's claude.exe included - the lock is stale. */
    @Test
    fun withoutAStartAnotherLiveProgramMakesTheLockStale() {
        val others = listOf(
            listOf("""C:\Program Files\WindowsApps\Claude_1.2.3.0_arm64__0123456789abc\app\claude.exe"""),
            listOf("""C:\Windows\System32\notepad.exe"""),
            listOf("""C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"""),
        )
        for (command in others) {
            for (start in listOf(written, written.plusSeconds(3_600))) {
                assertEquals("$command $start", windowsStale, windows(command, start))
            }
        }
    }

    /** The reason with a start keeps its rules under the Windows ones: a start that fits still vouches. */
    @Test
    fun aReasonWithAStartKeepsItsRulesOnWindows() {
        val notepad = listOf("""C:\Windows\System32\notepad.exe""")
        val fits = FakeProcessProbe().apply { add(9384, null, written.plusMillis(400), notepad) }
        assertEquals(WorktreeLock.Live(9384), of(fits, style = PathStyle.WINDOWS))
        val off = FakeProcessProbe().apply { add(9384, null, written.plusSeconds(60), notepad) }
        assertEquals(stale, of(off, style = PathStyle.WINDOWS))
        val node = FakeProcessProbe().apply { add(9384, null, written.plusSeconds(60), listOf("""C:\Program Files\nodejs\node.exe""")) }
        assertEquals(WorktreeLock.Live(9384), of(node, style = PathStyle.WINDOWS))
    }

    /**
     * Claude Code writes the start on macOS and Linux, so there a reason without one is somebody else's lock, as it
     * always was: git's refusal stands, whatever holds the pid.
     */
    @Test
    fun withoutAStartTheLockIsForeignOnMacOsAndLinux() {
        assertEquals(WorktreeLock.Foreign, of(FakeProcessProbe(), reason = windowsReason))
        for (command in listOf(claude, emptyList(), sleep, listOf("/Applications/Claude.app/Contents/MacOS/Claude"), listOf("/w/bin/claude-wrapper.sh"))) {
            assertEquals("$command", WorktreeLock.Foreign, of(FakeProcessProbe().apply { add(4528, null, written, command) }, reason = windowsReason))
        }
        // with a start the Unix rules stay as they were
        assertEquals(stale, of(FakeProcessProbe()))
    }

    /** On real Windows the four-argument call WorktreeCloseFlow makes reads by the Windows rules. */
    @Test
    fun onWindowsTheDefaultsReadAWindowsLock() {
        Fixture.assumeWindows("the default style is the running system's")
        fun holder(command: List<String>) = FakeProcessProbe().apply { add(4528, null, written.plusSeconds(3_600), command) }
        assertEquals(WorktreeLock.Live(4528), WorktreeLock.of(true, windowsReason, holder(windowsCli), ZoneOffset.UTC))
        assertEquals(WorktreeLock.Live(4528), WorktreeLock.of(true, windowsReason, holder(listOf("""C:\Program Files\nodejs\node.exe""")), ZoneOffset.UTC))
        val desktop = holder(listOf("""C:\Program Files\WindowsApps\Claude_1.2.3.0_arm64__0123456789abc\app\claude.exe"""))
        assertEquals(windowsStale, WorktreeLock.of(true, windowsReason, desktop, ZoneOffset.UTC))
        assertEquals(windowsStale, WorktreeLock.of(true, windowsReason, FakeProcessProbe(), ZoneOffset.UTC))
    }
}
