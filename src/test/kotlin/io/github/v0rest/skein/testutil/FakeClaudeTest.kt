package io.github.v0rest.skein.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rules a test starts the fake claude by. When a machine cannot run it the test is skipped, never failed. The
 * environment it gets keeps it away from the real Claude home, the launcher puts the very interpreter that answered on
 * PATH, and a fake that only its status file still names is stopped only while it is the process that file describes.
 */
class FakeClaudeTest {
    private val python = Path.of("/usr/bin/python3")

    @Test
    fun aMachineWithPython38AndAShellRunsIt() {
        assertNull(FakeClaude.skipReason(python, "3.8", shellPresent = true))
        assertNull(FakeClaude.skipReason(python, "3.12", shellPresent = true))
        assertNull(FakeClaude.skipReason(python, "4.0", shellPresent = true))
    }

    @Test
    fun aMissingOrOldPythonOrShellSaysWhy() {
        assertEquals("python3 is not on PATH, so the fake claude cannot run", FakeClaude.skipReason(null, null, shellPresent = true))
        assertEquals("python3 at /usr/bin/python3 did not say its version", FakeClaude.skipReason(python, null, shellPresent = true))
        assertEquals("python3 at /usr/bin/python3 is 3.7, the fake claude needs 3.8 or newer", FakeClaude.skipReason(python, "3.7", shellPresent = true))
        assertEquals("python3 at /usr/bin/python3 did not say its version", FakeClaude.skipReason(python, "three", shellPresent = true))
        assertEquals("/bin/sh is missing, so no shell can start the fake claude", FakeClaude.skipReason(python, "3.9", shellPresent = false))
    }

    private val root = Path.of("/t/rig")
    private val realUserHome = Path.of("/home/acme")

    private fun environment(claudeHome: Path = root.resolve("claude-home"), userHome: Path = root.resolve("home"), extra: Map<String, String> = emptyMap()) =
        FakeClaude.environment(claudeHome, userHome, bin = root.resolve("bin"), ceiling = root, realUserHome = realUserHome, extra = extra)

    /** Built from nothing, so no CLAUDE_* variable of the machine (a Claude Code session running the build) leaks in. */
    @Test
    fun theEnvironmentIsBuiltFromNothing() {
        val env = environment(extra = mapOf("FAKE_CLAUDE_SCENARIO" to "/t/rig/scenario.json"))
        assertEquals(
            mapOf(
                "HOME" to "/t/rig/home",
                "PATH" to "/t/rig/bin:/usr/bin:/bin",
                "CLAUDE_CONFIG_DIR" to "/t/rig/claude-home",
                "GIT_CEILING_DIRECTORIES" to "/t/rig",
                "GIT_CONFIG_NOSYSTEM" to "1",
                "FAKE_CLAUDE_SCENARIO" to "/t/rig/scenario.json",
            ),
            env,
        )
        assertTrue("the fake's folder comes first on PATH", env.getValue("PATH").startsWith("/t/rig/bin:"))
    }

    @Test
    fun theRealClaudeHomeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { environment(claudeHome = realUserHome.resolve(".claude")) }
        assertThrows(IllegalArgumentException::class.java) { environment(claudeHome = realUserHome.resolve(".claude/sub")) }
        assertThrows(IllegalArgumentException::class.java) { environment(claudeHome = realUserHome.resolve("x/../.claude")) }
        // a folder above it would put sessions/ and projects/ right next to the real one
        assertThrows(IllegalArgumentException::class.java) { environment(claudeHome = realUserHome) }
        assertThrows(IllegalArgumentException::class.java) { environment(claudeHome = Path.of("/")) }
    }

    /** With the real HOME, `~/.claude` would be the real one for the fake and for whatever it starts. */
    @Test
    fun theRealHomeIsRefusedAsTheFakesHome() {
        assertThrows(IllegalArgumentException::class.java) { environment(userHome = realUserHome) }
        assertThrows(IllegalArgumentException::class.java) { environment(userHome = Path.of("/home")) }
    }

    @Test
    fun aRelativeClaudeHomeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { environment(claudeHome = Path.of("claude-home")) }
    }

    @Test
    fun onlyTheFakesOwnVariablesComeOnTop() {
        assertThrows(IllegalArgumentException::class.java) { environment(extra = mapOf("CLAUDE_CONFIG_DIR" to "/elsewhere")) }
        assertThrows(IllegalArgumentException::class.java) { environment(extra = mapOf("CLAUDECODE" to "1")) }
        assertThrows(IllegalArgumentException::class.java) { environment(extra = mapOf("HOME" to "/home/acme")) }
        assertEquals("/t/rig/gates", environment(extra = mapOf("FAKE_CLAUDE_GATES" to "/t/rig/gates"))["FAKE_CLAUDE_GATES"])
    }

    @Test
    fun theLauncherExecsPythonOnTheScript() {
        assertEquals(
            "#!/bin/sh\n# made by a test: runs the fake claude with this python3\nexec '/usr/bin/python3' '/w/tools/fake-claude/bin/claude' \"\$@\"\n",
            FakeClaude.launcher(python, Path.of("/w/tools/fake-claude/bin/claude")),
        )
    }

    @Test
    fun theLauncherQuotesAnyPath() {
        val launcher = FakeClaude.launcher(Path.of("/opt/py thon/python3"), Path.of("/w/it's/claude"))
        assertTrue(launcher, launcher.contains("exec '/opt/py thon/python3' '/w/it'\\''s/claude' \"\$@\""))
    }

    /** The probe prints the version and the interpreter itself, so the fake runs on that file and not on a shim. */
    @Test
    fun theProbeNamesTheInterpreterItself() {
        assertEquals(FakeClaude.Interpreter("3.12", Path.of("/opt/py/bin/python3.12")), FakeClaude.interpreterOf("3.12\n/opt/py/bin/python3.12\n"))
        assertEquals(FakeClaude.Interpreter("3.9", Path.of("/usr/bin/python3")), FakeClaude.interpreterOf("3.9\r\n/usr/bin/python3\r\n"))
    }

    @Test
    fun aProbeWithoutAnAbsoluteInterpreterIsNone() {
        assertNull(FakeClaude.interpreterOf(""))
        assertNull(FakeClaude.interpreterOf("3.12\n"))
        assertNull(FakeClaude.interpreterOf("3.12\n\n"))
        assertNull(FakeClaude.interpreterOf("3.12\npython3\n"))
        assertNull(FakeClaude.interpreterOf("Traceback (most recent call last):\n"))
    }

    /** Started when its file says, as Skein's watcher checks, so a pid some other program got since is left alone. */
    @Test
    fun aFiledFakeIsStoppedOnlyWhileItIsTheProcessItsFileDescribes() {
        val start = 1_000_000L
        assertTrue(FakeClaude.isFiledProcess(startedAt = start + 100, processStartMillis = start))
        assertTrue("clocks a little apart", FakeClaude.isFiledProcess(startedAt = start - 1_500, processStartMillis = start))
        assertFalse("a process that started after the file was written", FakeClaude.isFiledProcess(startedAt = start - 10_000, processStartMillis = start))
        assertFalse("a process far older than the file", FakeClaude.isFiledProcess(startedAt = start + 600_000, processStartMillis = start))
    }

    /** Without the bit a checkout cannot put the fake on PATH as `claude`, which the sandbox needs. */
    @Test
    fun theFakeIsExecutable() {
        assertTrue("${FakeClaude.script} is missing or not executable", Files.isExecutable(FakeClaude.script))
    }
}
