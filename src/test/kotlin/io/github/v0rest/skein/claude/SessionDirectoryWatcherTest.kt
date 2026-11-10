package io.github.v0rest.skein.claude

import io.github.v0rest.skein.testutil.CountingClaudeHome
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.TreeDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SessionDirectoryWatcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The instant "Thu Nov  5 21:35:49 2026" names in the system zone, which is how Claude Code writes procStart. */
    private val processStart: Instant = LocalDateTime.of(2026, 11, 5, 21, 35, 49).atZone(ZoneId.systemDefault()).toInstant()
    private val procStart = "Thu Nov  5 21:35:49 2026"
    private val probe = FakeProcessProbe(current = 1)
    private lateinit var home: ClaudeHome
    private lateinit var watcher: SessionDirectoryWatcher

    @Before
    fun setUp() {
        home = ClaudeHome(tmp.root.toPath().resolve(".claude"))
        Files.createDirectories(home.sessionsDir)
        probe.add(500, parent = 10, start = processStart)
        watcher = SessionDirectoryWatcher(home, probe)
    }

    private fun sessionFile(pid: Long = 500): Path = home.sessionsDir.resolve("$pid.json")

    private fun write(pid: Long, json: String) {
        Files.writeString(sessionFile(pid), json)
        // the tests may rewrite a file within one millisecond; push the mtime so the stamp changes like a real rewrite
        val previous = Files.getLastModifiedTime(sessionFile(pid)).toMillis()
        Files.setLastModifiedTime(sessionFile(pid), java.nio.file.attribute.FileTime.fromMillis(previous + 1000))
    }

    private fun idle(extra: String = ""): String = """{"pid":500,"status":"idle","procStart":"$procStart"$extra}"""

    @Test
    fun liveSessionIsReported() {
        write(500, idle())
        assertEquals(ClaudeActivity.IDLE, watcher.poll().sessions.getValue(500).activity)
    }

    @Test
    fun deadPidIsIgnored() {
        write(500, idle())
        probe.kill(500)
        assertTrue(watcher.poll().sessions.isEmpty())
    }

    @Test
    fun procStartMismatchIsIgnored() {
        probe.add(500, parent = 10, start = processStart.plusSeconds(10))
        write(500, idle())
        assertTrue(watcher.poll().sessions.isEmpty())
    }

    @Test
    fun aDriftedClockStillSeesALiveClaude() {
        // on Linux the JDK derives start instants from a boot time cached at JVM start; after a suspend or an NTP
        // step they drift from the wall clock claude wrote, so the process itself has to vouch for the file
        probe.add(500, parent = 10, start = processStart.plusSeconds(600), command = listOf("/Users/u/.local/share/claude/versions/2.1.318"))
        write(500, idle())
        assertEquals(setOf(500L), watcher.poll().sessions.keys)
    }

    @Test
    fun aNodeInstallOfClaudeIsRecognised() {
        probe.add(500, parent = 10, start = processStart.plusSeconds(600), command = listOf("/usr/bin/node", "/usr/bin/claude"))
        write(500, idle())
        assertEquals(setOf(500L), watcher.poll().sessions.keys)
    }

    @Test
    fun aReusedPidThatIsNotClaudeIsStale() {
        probe.add(500, parent = 10, start = processStart.plusSeconds(600), command = listOf("/bin/zsh", "-l"))
        write(500, idle())
        assertTrue(watcher.poll().sessions.isEmpty())
    }

    @Test
    fun onlyTheExecutableCountsNotItsArguments() {
        for (command in listOf(listOf("/usr/bin/tail", "-f", "/tmp/claude-code.log"), listOf("/usr/bin/vim", "/home/u/src/claude"), listOf("/usr/bin/node", "/srv/claude-code-router/index.js"))) {
            probe.add(500, parent = 10, start = processStart.plusSeconds(600), command = command)
            write(500, idle())
            assertTrue("accepted a stale file for $command", watcher.poll().sessions.isEmpty())
        }
    }

    @Test
    fun procStartWithinTwoSecondsIsLive() {
        probe.add(500, parent = 10, start = processStart.plusMillis(1500))
        write(500, idle())
        assertEquals(setOf(500L), watcher.poll().sessions.keys)
    }

    @Test
    fun missingProcStartFallsBackToStartedAt() {
        val startMillis = processStart.toEpochMilli()
        write(500, """{"pid":500,"status":"idle","startedAt":${startMillis + 3_000}}""")
        assertEquals(setOf(500L), watcher.poll().sessions.keys)
        write(500, """{"pid":500,"status":"idle","startedAt":${startMillis + 600_000}}""")
        assertTrue(watcher.poll().sessions.isEmpty())
    }

    @Test
    fun procStartMismatchButStartedAtMatchesIsLive() {
        // the shell may run in another time zone than the IDE, so procStart (a local-time string) can be hours off;
        // startedAt is epoch millis and decides first
        val startMillis = processStart.toEpochMilli()
        write(500, """{"pid":500,"status":"idle","procStart":"Thu Nov  5 23:35:49 2026","startedAt":${startMillis + 3_000}}""")
        assertEquals(setOf(500L), watcher.poll().sessions.keys)
    }

    @Test
    fun startedAtLongBeforeProcessStartIsIgnored() {
        val startMillis = processStart.toEpochMilli()
        write(500, """{"pid":500,"status":"idle","procStart":"$procStart","startedAt":${startMillis - 10_000}}""")
        assertTrue(watcher.poll().sessions.isEmpty())
    }

    @Test
    fun unchangedFileIsNotReadAgainOutsideTheRecentWindow() {
        val counting = CountingClaudeHome(home.root)
        val farAhead = { System.currentTimeMillis() + 60_000 } // every file looks old to this watcher
        val w = SessionDirectoryWatcher(counting, probe, clock = farAhead)
        write(500, idle(""","name":"a""""))
        w.poll()
        val once = counting.bytesRead.getValue(sessionFile(500))
        w.poll()
        assertEquals(once, counting.bytesRead.getValue(sessionFile(500)))
        write(500, idle(""","name":"bb""""))
        assertEquals("bb", w.poll().sessions.getValue(500).name)
        assertTrue(counting.bytesRead.getValue(sessionFile(500)) > once)
    }

    @Test
    fun recentlyModifiedFileIsReReadEvenWithTheSameStamp() {
        Files.writeString(sessionFile(500), idle(""","name":"a""""))
        val stamp = Files.getLastModifiedTime(sessionFile(500))
        assertEquals("a", watcher.poll().sessions.getValue(500).name)
        Files.writeString(sessionFile(500), idle(""","name":"b""""))
        Files.setLastModifiedTime(sessionFile(500), stamp) // same size, same mtime: only the recent window can catch it
        assertEquals("b", watcher.poll().sessions.getValue(500).name)
    }

    @Test
    fun neitherProcStartNorStartedAtNeedsOnlyAliveness() {
        write(500, """{"pid":500,"status":"busy"}""")
        assertEquals(ClaudeActivity.BUSY, watcher.poll().sessions.getValue(500).activity)
    }

    @Test
    fun partialJsonKeepsLastGood() {
        write(500, idle())
        assertEquals(ClaudeActivity.IDLE, watcher.poll().sessions.getValue(500).activity)
        write(500, """{"pid":500,"sta""")
        assertEquals(ClaudeActivity.IDLE, watcher.poll().sessions.getValue(500).activity)
    }

    @Test
    fun partialJsonIsReReadOnceComplete() {
        write(500, idle())
        watcher.poll()
        write(500, """{"pid":500,"sta""")
        watcher.poll()
        write(500, """{"pid":500,"status":"busy","procStart":"$procStart"}""")
        assertEquals(ClaudeActivity.BUSY, watcher.poll().sessions.getValue(500).activity)
    }

    @Test
    fun unrecognizedShapeIsFlagged() {
        write(500, """{"pid":500}""")
        val snapshot = watcher.poll()
        assertEquals(setOf(500L), snapshot.unrecognized)
        assertFalse(500L in snapshot.sessions)
    }

    @Test
    fun unrecognizedDeadPidIsNotFlagged() {
        write(500, """{"pid":500}""")
        probe.kill(500)
        assertTrue(watcher.poll().unrecognized.isEmpty())
    }

    @Test
    fun fileNamedForAnotherPidIsUnrecognized() {
        write(500, """{"pid":501,"status":"idle"}""")
        val snapshot = watcher.poll()
        assertEquals(setOf(500L), snapshot.unrecognized)
        assertTrue(snapshot.sessions.isEmpty())
    }

    @Test
    fun nonPidFileNamesAreIgnored() {
        Files.writeString(home.sessionsDir.resolve("notes.json"), """{"pid":500,"status":"idle"}""")
        Files.writeString(home.sessionsDir.resolve("500.key"), "secret")
        Files.writeString(home.sessionsDir.resolve(".fleetview-heartbeat"), "1")
        val snapshot = watcher.poll()
        assertTrue(snapshot.sessions.isEmpty())
        assertTrue(snapshot.unrecognized.isEmpty())
    }

    @Test
    fun removedFileDisappears() {
        write(500, idle())
        assertEquals(setOf(500L), watcher.poll().sessions.keys)
        Files.delete(sessionFile(500))
        assertTrue(watcher.poll().sessions.isEmpty())
    }

    @Test
    fun nameChangeIsReported() {
        write(500, idle(""","name":"a""""))
        assertEquals("a", watcher.poll().sessions.getValue(500).name)
        write(500, idle(""","name":"b""""))
        assertEquals("b", watcher.poll().sessions.getValue(500).name)
    }

    @Test
    fun missingSessionsDirIsEmptySnapshot() {
        val emptyHome = ClaudeHome(tmp.root.toPath().resolve("elsewhere"))
        assertEquals(SessionSnapshot.EMPTY, SessionDirectoryWatcher(emptyHome, probe).poll())
    }

    @Test
    fun oneFailingFileDoesNotFreezeTheSnapshot() {
        probe.add(501, parent = 10, start = processStart)
        write(500, idle())
        write(501, """{"pid":501,"status":"busy","procStart":"$procStart"}""")
        val failingHome = object : ClaudeHome(home.root) {
            override fun readText(path: Path, maxBytes: Int): String? =
                if (path.fileName.toString() == "501.json") throw IllegalStateException("boom") else super.readText(path, maxBytes)
        }
        val snapshot = SessionDirectoryWatcher(failingHome, probe).poll()
        assertEquals(setOf(500L), snapshot.sessions.keys)
        assertEquals(setOf(501L), snapshot.unrecognized)
    }

    @Test
    fun watcherNeverWrites() {
        write(500, idle())
        Files.writeString(home.sessionsDir.resolve("501.json"), """{"pid":501,"sta""")
        val before = TreeDigest.of(home.root)
        repeat(5) { watcher.poll() }
        assertEquals(before, TreeDigest.of(home.root))
    }
}
