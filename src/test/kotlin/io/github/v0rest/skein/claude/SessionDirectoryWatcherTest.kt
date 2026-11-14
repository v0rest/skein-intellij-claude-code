package io.github.v0rest.skein.claude

import io.github.v0rest.skein.claude.ClaudeIdentity.CLI
import io.github.v0rest.skein.claude.ClaudeIdentity.OTHER
import io.github.v0rest.skein.claude.ClaudeIdentity.UNKNOWN
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.CountingClaudeHome
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.Fixture
import io.github.v0rest.skein.testutil.TreeDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.time.ZoneOffset

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
        // the rows below were written with macOS and Linux commands, so they are read by those rules on any system
        watcher = SessionDirectoryWatcher(home, probe, style = PathStyle.UNIX)
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
        val w = SessionDirectoryWatcher(counting, probe, clock = farAhead, style = PathStyle.UNIX)
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
        val snapshot = SessionDirectoryWatcher(failingHome, probe, style = PathStyle.UNIX).poll()
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

    // on Windows procStart is a FILETIME and a process's command is its executable alone, as seen on Windows 11

    /** A FILETIME a Windows 11 status file had, converted on its own (1601-01-01 plus the ticks, with Python's datetime). */
    private val fileTime = "134390832698011131"
    private val fileTimeInstant: Instant = Instant.parse("2026-11-13T22:41:09.8011131Z")
    private val windowsCli = listOf(Fixture.root("Users/acme/.local/bin/claude.exe", PathStyle.WINDOWS))
    private val claudeDesktop = listOf("""C:\Program Files\WindowsApps\Claude_1.2.3.0_arm64__0123456789abc\app\claude.exe""")
    private val nodeWithoutArguments = listOf("""C:\Program Files\nodejs\node.exe""")

    @Test
    fun aFileTimeIsReadAsHundredNanosecondTicksSince1601() {
        assertEquals(fileTimeInstant, SessionDirectoryWatcher.procStartInstant(fileTime, pidDomain = null))
        assertEquals(fileTimeInstant, SessionDirectoryWatcher.procStartInstant(fileTime, pidDomain = "win32:acme-pc"))
        // the first tick of 1970, and the largest FILETIME, which Windows documents as 30828-09-14 02:48:05.4775807
        assertEquals(Instant.EPOCH, SessionDirectoryWatcher.procStartInstant("116444736000000000", pidDomain = null))
        assertEquals(Instant.parse("+30828-09-14T02:48:05.4775807Z"), SessionDirectoryWatcher.procStartInstant(Long.MAX_VALUE.toString(), pidDomain = null))
    }

    /** Fewer than 17 digits is a FILETIME only when pidDomain says the file comes from Windows. */
    @Test
    fun aShortNumberIsAFileTimeOnlyWithAWindowsPidDomain() {
        val short = "1000000000000000" // 16 digits, 1604-03-03
        assertNull(SessionDirectoryWatcher.procStartInstant(short, pidDomain = null))
        assertNull(SessionDirectoryWatcher.procStartInstant(short, pidDomain = "darwin:acme-mac"))
        assertEquals(Instant.parse("1604-03-03T09:46:40Z"), SessionDirectoryWatcher.procStartInstant(short, pidDomain = "win32:acme-pc"))
        // only "win32:" says Windows
        for (domain in listOf("win", "winx:acme-pc", "win32", "WIN32:acme-pc", " win32:acme-pc")) {
            assertNull(domain, SessionDirectoryWatcher.procStartInstant(short, pidDomain = domain))
        }
        // 17 digits is a FILETIME without any pidDomain
        assertEquals(Instant.parse("1632-09-09T01:46:40Z"), SessionDirectoryWatcher.procStartInstant("10000000000000000", pidDomain = null))
    }

    /** What cannot be read as either form proves nothing, and never throws. */
    @Test
    fun anUnreadableProcStartIsNull() {
        val unreadable = listOf(
            "", " ", "99999999999999999999", "9223372036854775808", "-134390832698011131", "+134390832698011131",
            "13439083269801113x", " 134390832698011131", "134390832698011131 ", "1343908326980111.1",
            "\uFF11\uFF13\uFF14\uFF13\uFF19\uFF10\uFF18\uFF13\uFF12\uFF16\uFF19\uFF18\uFF10\uFF11\uFF11\uFF11\uFF13", "someday",
        )
        for (text in unreadable) {
            assertNull(text, SessionDirectoryWatcher.procStartInstant(text, pidDomain = null))
            assertNull(text, SessionDirectoryWatcher.procStartInstant(text, pidDomain = "win32:acme-pc"))
        }
    }

    /** The `ps` form stays as it was, whatever pidDomain says. */
    @Test
    fun thePsFormIsStillReadInTheGivenZone() {
        val utc = Instant.parse("2026-11-05T21:35:49Z")
        assertEquals(utc, SessionDirectoryWatcher.procStartInstant(procStart, pidDomain = null, zone = ZoneOffset.UTC))
        assertEquals(utc, SessionDirectoryWatcher.procStartInstant(procStart, pidDomain = "win32:acme-pc", zone = ZoneOffset.UTC))
        assertEquals(processStart, SessionDirectoryWatcher.procStartInstant(procStart, pidDomain = null))
    }

    private fun windowsWatcher() = SessionDirectoryWatcher(home, probe, style = PathStyle.WINDOWS)

    /** A Windows status file without startedAt: its FILETIME must name the process's start, which the JDK reports to the millisecond. */
    private fun windowsIdle(procStart: String = fileTime): String =
        """{"pid":500,"status":"idle","cwd":"${Fixture.jsonEscaped(Fixture.root("w/demo-shop", PathStyle.WINDOWS))}","procStart":"$procStart","pidDomain":"win32:acme-pc"}"""

    @Test
    fun aFileTimeThatNamesTheProcessStartIsLive() {
        probe.add(500, parent = 10, start = fileTimeInstant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        write(500, windowsIdle())
        assertEquals(setOf(500L), windowsWatcher().poll().sessions.keys)
    }

    /** A pid Windows gave to another process since: its start is off and its command is not the CLI's, or unknown. */
    @Test
    fun aFileTimeThatNamesAnotherStartIsStaleUnlessTheCliVouches() {
        for (command in listOf(emptyList(), nodeWithoutArguments, claudeDesktop, listOf("""C:\Windows\System32\notepad.exe"""))) {
            probe.add(500, parent = 10, start = fileTimeInstant.plusSeconds(10), command = command)
            write(500, windowsIdle())
            assertTrue("accepted a stale file for $command", windowsWatcher().poll().sessions.isEmpty())
        }
        probe.add(500, parent = 10, start = fileTimeInstant.plusSeconds(10), command = windowsCli)
        write(500, windowsIdle())
        assertEquals(setOf(500L), windowsWatcher().poll().sessions.keys)
    }

    /** Nothing the system does not tell drops a live pid on its own: no start, or a FILETIME that cannot be read. */
    @Test
    fun whatTheSystemDoesNotTellKeepsAWindowsSessionLive() {
        probe.add(500, parent = 10, start = fileTimeInstant.plusSeconds(10), command = emptyList())
        probe.unknownStart += 500L
        write(500, windowsIdle())
        assertEquals(setOf(500L), windowsWatcher().poll().sessions.keys)
        probe.unknownStart.clear()
        write(500, windowsIdle(procStart = "99999999999999999999"))
        assertEquals(setOf(500L), windowsWatcher().poll().sessions.keys)
    }

    /** startedAt decides first on Windows too: there it was seen 410 ms after the process start. */
    @Test
    fun startedAtStillDecidesFirstOnWindows() {
        probe.add(500, parent = 10, start = fileTimeInstant, command = emptyList())
        write(500, """{"pid":500,"status":"idle","startedAt":${fileTimeInstant.toEpochMilli() + 410},"procStart":"1","pidDomain":"win32:acme-pc"}""")
        assertEquals(setOf(500L), windowsWatcher().poll().sessions.keys)
    }

    // Which process is Claude Code's CLI, by its command

    private fun identify(style: PathStyle, vararg command: String) = SessionDirectoryWatcher.identify(command.toList(), style)

    /**
     * On Windows the JDK reports the full executable path and never the arguments. The CLI is claude.exe in
     * any case, or a file below claude\versions, with either separator.
     */
    @Test
    fun theWindowsCliIsClaudeExeOrAFileBelowItsVersionsFolder() {
        val cli = listOf(
            """C:\Users\acme\.local\bin\claude.exe""",
            """C:\Users\acme\.local\bin\CLAUDE.EXE""",
            """C:\Users\acme\.local\bin\Claude.exe""",
            "C:/Users/acme/.local/bin/claude.exe",
            """C:\Users\acme\.local\share\claude\versions\2.1.325""",
            """C:\Users\acme\.local\share\Claude\Versions\2.1.325\claude.exe""",
            "C:/Users/acme/.local/share/claude/versions/2.1.325",
            """\\?\C:\Users\acme\.local\bin\claude.exe""",
        )
        for (executable in cli) assertEquals(executable, CLI, identify(PathStyle.WINDOWS, executable))
    }

    /**
     * Claude Desktop's processes are claude.exe too, in its package folder `Claude_<version>_<arch>__<id>` right under
     * WindowsApps (an app execution alias folder for it has the same shape): the name alone is not the CLI there.
     */
    @Test
    fun claudeExeUnderWindowsAppsIsClaudeDesktopNotTheCli() {
        val desktop = listOf(
            claudeDesktop.single(),
            """C:\Program Files\WindowsApps\Claude_1.2.3.0_x64__0123456789abc\app\Claude.exe""",
            "C:/Program Files/WindowsApps/Claude_1.2.3.0_arm64__0123456789abc/app/claude.exe",
            """C:\Program Files\windowsapps\Claude_1.2.3.0_arm64__0123456789abc\claude.exe""",
            """C:\Users\acme\AppData\Local\Microsoft\WindowsApps\Claude_0123456789abc\claude.exe""",
            """C:\Program Files\WindowsApps\claude_1.2.3.0_arm64__0123456789abc\app\CLAUDE.EXE""",
        )
        for (executable in desktop) assertEquals(executable, OTHER, identify(PathStyle.WINDOWS, executable))
        // a WindowsApps folder that holds no Claude package is no sign of Claude Desktop
        assertEquals(CLI, identify(PathStyle.WINDOWS, """C:\Users\WindowsApps\.local\bin\claude.exe"""))
        assertEquals(CLI, identify(PathStyle.WINDOWS, """C:\Program Files\WindowsApps\Other_1.0.0.0_x64__0123456789abc\claude.exe"""))
        assertEquals(CLI, identify(PathStyle.WINDOWS, """C:\w\WindowsApps\Claude_1\claude\versions\2.1.325"""))
    }

    /** node.exe with no arguments may be running the claude script or anything else: Windows does not say which. */
    @Test
    fun nodeOnWindowsIsUnknownUnlessItsScriptIsClaude() {
        assertEquals(UNKNOWN, identify(PathStyle.WINDOWS, nodeWithoutArguments.single()))
        assertEquals(UNKNOWN, identify(PathStyle.WINDOWS, """C:\Program Files\nodejs\NODE.EXE"""))
        // npm's claude.cmd runs node with cli.js, so a script by another name is still no proof on Windows
        assertEquals(UNKNOWN, identify(PathStyle.WINDOWS, nodeWithoutArguments.single(), """C:\Users\acme\AppData\Roaming\npm\node_modules\x\cli.js"""))
        assertEquals(CLI, identify(PathStyle.WINDOWS, nodeWithoutArguments.single(), """C:\Users\acme\AppData\Roaming\npm\claude"""))
    }

    /** bun and deno can run the claude script too, and Windows does not tell their script either. */
    @Test
    fun bunAndDenoOnWindowsAreUnknownLikeNode() {
        for (runtime in listOf("""C:\Users\acme\.bun\bin\bun.exe""", """C:\Users\acme\.bun\bin\BUN.EXE""", """C:\Users\acme\.deno\bin\deno.exe""")) {
            assertEquals(runtime, UNKNOWN, identify(PathStyle.WINDOWS, runtime))
            assertEquals(runtime, UNKNOWN, identify(PathStyle.WINDOWS, runtime, """C:\w\cli.js"""))
            assertEquals(runtime, CLI, identify(PathStyle.WINDOWS, runtime, """C:\w\claude"""))
        }
        assertEquals(OTHER, identify(PathStyle.WINDOWS, """C:\w\bunny.exe"""))
        assertEquals(OTHER, identify(PathStyle.WINDOWS, """C:\w\denote.exe"""))
    }

    @Test
    fun otherWindowsProgramsAreNotTheCli() {
        val others = listOf(
            """C:\Windows\System32\notepad.exe""",
            """C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe""",
            """C:\w\claude-wrapper.exe""",
            """C:\w\claude.exe.bak""",
            """C:\w\claude.exe\tool.exe""",
            """C:\w\versions\claude\tool.exe""",
            """C:\w\claude\versions""",
        )
        for (executable in others) assertEquals(executable, OTHER, identify(PathStyle.WINDOWS, executable))
    }

    @Test
    fun noCommandIsUnknown() {
        for (style in PathStyle.entries) {
            assertEquals(UNKNOWN, SessionDirectoryWatcher.identify(emptyList(), style))
            assertEquals(UNKNOWN, identify(style, ""))
            assertEquals(UNKNOWN, identify(style, " "))
        }
    }

    /** macOS and Linux keep their rules: the name claude exactly, the versions folder, or node running the claude script. */
    @Test
    fun theUnixRulesStayAsTheyWere() {
        assertEquals(CLI, identify(PathStyle.UNIX, "/w/bin/claude"))
        assertEquals(CLI, identify(PathStyle.UNIX, "/w/bin/claude", "--worktree", "demo"))
        assertEquals(CLI, identify(PathStyle.UNIX, "/w/.local/share/claude/versions/2.1.0"))
        assertEquals(CLI, identify(PathStyle.UNIX, "/usr/bin/node", "/usr/bin/claude"))
        assertEquals(CLI, identify(PathStyle.UNIX, "/usr/bin/nodejs", "/usr/bin/claude", "--resume"))
        assertEquals(UNKNOWN, identify(PathStyle.UNIX, "/usr/bin/node"))
        assertEquals(OTHER, identify(PathStyle.UNIX, "/usr/bin/node", "/srv/claude-code-router/index.js"))
        assertEquals(OTHER, identify(PathStyle.UNIX, "/usr/bin/vim", "/home/u/src/claude"))
        assertEquals(OTHER, identify(PathStyle.UNIX, "/w/bin/CLAUDE"))
        assertEquals(OTHER, identify(PathStyle.UNIX, "/w/bin/claude.exe"))
        assertEquals(OTHER, identify(PathStyle.UNIX, "/w/claude/versions"))
        assertEquals(OTHER, identify(PathStyle.UNIX, "/usr/bin/NODE", "/usr/bin/claude"))
        assertEquals(OTHER, identify(PathStyle.UNIX, "/usr/local/bin/bun"))
        // WindowsApps means nothing on Unix
        assertEquals(CLI, identify(PathStyle.UNIX, "/w/WindowsApps/bin/claude"))
        assertEquals(CLI, identify(PathStyle.UNIX, "/w/WindowsApps/Claude_1/claude"))
        // a backslash is part of a name on Unix, so a Windows path there is one odd name
        assertEquals(OTHER, identify(PathStyle.UNIX, """C:\Users\acme\.local\bin\claude.exe"""))
    }

    /**
     * Unix-shaped commands keep their answers under the Windows rules, so a fixture written for the Mac reads the same
     * on windows-latest - but node's other scripts turn unknown there, since Windows never tells them.
     */
    @Test
    fun theWindowsRulesReadUnixShapedCommandsAlike() {
        assertEquals(CLI, identify(PathStyle.WINDOWS, "/w/bin/claude"))
        assertEquals(CLI, identify(PathStyle.WINDOWS, "/w/.local/share/claude/versions/2.1.0"))
        assertEquals(CLI, identify(PathStyle.WINDOWS, "/usr/bin/node", "/usr/bin/claude"))
        assertEquals(OTHER, identify(PathStyle.WINDOWS, "/bin/sleep", "60"))
        assertEquals(UNKNOWN, identify(PathStyle.WINDOWS, "/usr/bin/node", "/srv/claude-code-router/index.js"))
    }

    /** The watcher hands the file's pidDomain on, so a short FILETIME in a Windows file is read and can rule a pid out. */
    @Test
    fun aShortFileTimeInAWindowsFileIsCompared() {
        probe.add(500, parent = 10, start = Instant.parse("1604-03-03T09:46:40Z"))
        write(500, windowsIdle(procStart = "1000000000000000"))
        assertEquals(setOf(500L), windowsWatcher().poll().sessions.keys)
        probe.add(500, parent = 10, start = fileTimeInstant)
        write(500, windowsIdle(procStart = "1000000000000000"))
        assertTrue(windowsWatcher().poll().sessions.isEmpty())
    }

    /** On real Windows the watcher SessionMonitor builds, with no style given, reads by the Windows rules. */
    @Test
    fun onWindowsTheDefaultWatcherReadsWindowsFiles() {
        Fixture.assumeWindows("the default style is the running system's")
        val watcher = SessionDirectoryWatcher(home, probe)
        probe.add(500, parent = 10, start = fileTimeInstant.plusSeconds(10), command = windowsCli)
        write(500, windowsIdle())
        assertEquals(setOf(500L), watcher.poll().sessions.keys)
        probe.add(500, parent = 10, start = fileTimeInstant.plusSeconds(10), command = claudeDesktop)
        write(500, windowsIdle())
        assertTrue(watcher.poll().sessions.isEmpty())
    }
}
