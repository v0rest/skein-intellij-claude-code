package io.github.v0rest.skein.claude

import io.github.v0rest.skein.testutil.FakeProcessProbe
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
            "claude session demo (pid 9384)",
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
            "claude session demo (pid 9384)",
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

    private fun of(probe: FakeProcessProbe, reason: String? = this.reason, locked: Boolean = true, zone: ZoneId = ZoneOffset.UTC) =
        WorktreeLock.of(locked, reason, probe, zone)

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
}
