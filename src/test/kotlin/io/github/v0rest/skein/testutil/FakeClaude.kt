package io.github.v0rest.skein.testutil

import io.github.v0rest.skein.claude.SessionDirectoryWatcher
import io.github.v0rest.skein.claude.SessionFileParse
import io.github.v0rest.skein.claude.SessionFileParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.name

/**
 * The fake `claude` in tools/fake-claude for tests, a python3 script that writes the status files and transcript lines
 * Skein reads, step by step as a scenario says (tools/fake-claude/README.md). This object holds the rules a test starts
 * it by, pure and unit-tested in [FakeClaudeTest], and finds python3 on the machine. [FakeClaudeRig] sets one test up.
 */
object FakeClaude {
    /** The scenario file the fake follows, when the test gives one. */
    const val SCENARIO = "FAKE_CLAUDE_SCENARIO"

    /** The folder whose files open the fake's `wait` steps. Without it the fake waits for Enter instead. */
    const val GATES = "FAKE_CLAUDE_GATES"

    /** The file that marks a Claude home as the fake's own. The fake writes into no other. */
    const val MARKER = ".fake-claude-home"

    const val SHELL = "/bin/sh"
    private const val OWN_PREFIX = "FAKE_CLAUDE_"
    private val VERSION = Regex("^(\\d+)\\.(\\d+)")

    /** What a python3 prints to say what it is, its version and then the interpreter file itself. */
    private const val PROBE = "import sys; print('%d.%d' % sys.version_info[:2]); print(sys.executable)"

    /** The fake itself and the scenarios that come with it. Gradle runs the tests in the project folder. */
    val script: Path get() = Path.of("tools/fake-claude/bin/claude").toAbsolutePath()
    val scenarios: Path get() = Path.of("tools/fake-claude/scenarios").toAbsolutePath()

    /** A python3 as its probe described it, the interpreter file a test then runs the fake with. */
    data class Interpreter(val version: String, val executable: Path)

    /** Why this machine cannot run the fake ([python] 3.8 or newer, and [SHELL]), or null when it can. */
    fun skipReason(python: Path?, version: String?, shellPresent: Boolean): String? {
        if (python == null) return "python3 is not on PATH, so the fake claude cannot run"
        val (major, minor) = version?.let { VERSION.find(it) }?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
            ?: return "python3 at $python did not say its version"
        if (major < 3 || (major == 3 && minor < 8)) return "python3 at $python is $version, the fake claude needs 3.8 or newer"
        if (!shellPresent) return "$SHELL is missing, so no shell can start the fake claude"
        return null
    }

    /** The interpreter the probe's [output] names, or null when it names no absolute interpreter file. */
    fun interpreterOf(output: String): Interpreter? {
        val lines = output.lines().map(String::trim)
        val version = lines.getOrNull(0)?.takeIf { VERSION.containsMatchIn(it) } ?: return null
        val executable = lines.getOrNull(1)?.takeIf(String::isNotEmpty)?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        return if (executable.isAbsolute) Interpreter(version, executable) else null
    }

    /**
     * Whether a live process that started at [processStartMillis] is the one a status file written with [startedAt]
     * describes, by the window Skein's own watcher uses. A pid another program got since started later and fails.
     */
    fun isFiledProcess(startedAt: Long, processStartMillis: Long): Boolean =
        startedAt >= processStartMillis - SessionDirectoryWatcher.START_TOLERANCE_MILLIS &&
            startedAt <= processStartMillis + SessionDirectoryWatcher.STARTUP_WINDOW_MILLIS

    /**
     * The whole environment of a shell or fake a test starts, built from nothing so that no variable of the machine gets
     * in (a Claude Code session running the build has several). [userHome] is HOME, [bin] comes first on PATH,
     * [claudeHome] is CLAUDE_CONFIG_DIR, git stays inside [ceiling] and away from the machine's own settings, and [extra]
     * comes on top, which may only hold the fake's own `FAKE_CLAUDE_` variables. A Claude home that is the real one
     * ([realUserHome]'s `.claude`), lies inside it or holds it is refused, and so is a HOME whose `.claude` would be the
     * real one. Paths are compared as given, so pass them with links resolved.
     */
    fun environment(
        claudeHome: Path,
        userHome: Path,
        bin: Path,
        ceiling: Path,
        realUserHome: Path,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        for (path in listOf(claudeHome, userHome, bin, ceiling)) require(path.isAbsolute) { "not an absolute path: $path" }
        val realClaudeHome = realUserHome.normalize().resolve(".claude")
        val home = claudeHome.normalize()
        require(!home.startsWith(realClaudeHome) && !realClaudeHome.startsWith(home)) { "the fake's Claude home $claudeHome would touch the real $realClaudeHome" }
        require(!realUserHome.normalize().startsWith(userHome.normalize())) { "HOME $userHome would make the real $realClaudeHome the fake's ~/.claude" }
        val foreign = extra.keys.filterNot { it.startsWith(OWN_PREFIX) }
        require(foreign.isEmpty()) { "only $OWN_PREFIX variables may be added, not $foreign" }
        return linkedMapOf(
            "HOME" to userHome.toString(),
            "PATH" to "$bin:/usr/bin:/bin",
            "CLAUDE_CONFIG_DIR" to claudeHome.toString(),
            "GIT_CEILING_DIRECTORIES" to ceiling.toString(),
            "GIT_CONFIG_NOSYSTEM" to "1",
        ) + extra
    }

    /** The `claude` a test puts first on PATH, a shell script that execs [python] on the fake [script], so the pid stays. */
    fun launcher(python: Path, script: Path): String =
        "#!/bin/sh\n# made by a test: runs the fake claude with this python3\nexec ${quote(python.toString())} ${quote(script.toString())} \"\$@\"\n"

    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private val python: Path? by lazy { findPython() }

    /**
     * The interpreter behind the python3 on PATH. The build's own environment finds it, which a version manager's shim
     * needs, and the file it names must then answer once more with next to nothing set, as the fake runs.
     */
    private val interpreter: Interpreter? by lazy {
        val named = python?.let { probe(it, clean = false) } ?: return@lazy null
        probe(named.executable, clean = true)?.let { Interpreter(it.version, named.executable) }
    }

    /**
     * The interpreter file for the fake, after skipping the calling test (JUnit's assume) when this machine cannot run
     * it. A missing fake fails instead, since that is a broken checkout and not a missing tool.
     */
    fun requireRunnable(): Path {
        val found = interpreter
        val reason = skipReason(found?.executable ?: python, found?.version, Files.isExecutable(Path.of(SHELL)))
        assumeTrue(reason ?: "", reason == null)
        assertTrue("the fake claude is missing at $script", Files.isRegularFile(script))
        return found!!.executable
    }

    private fun findPython(): Path? =
        System.getenv("PATH").orEmpty().split(':').filter(String::isNotEmpty)
            .map { Path.of(it, "python3") }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    /** Runs [PROBE] with [python], in the build's environment or, [clean], with PATH alone, as the fake gets it. */
    private fun probe(python: Path, clean: Boolean): Interpreter? =
        try {
            val builder = ProcessBuilder(python.toString(), "-c", PROBE).redirectErrorStream(true)
            if (clean) {
                builder.environment().clear()
                builder.environment()["PATH"] = "/usr/bin:/bin"
            }
            val process = builder.start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                val output = process.inputStream.bufferedReader().readText()
                if (process.exitValue() == 0) interpreterOf(output) else null
            }
        } catch (e: IOException) {
            null
        }
}

/**
 * One test's fake claude, all of it under [root], a temporary folder. It holds the Claude home, marked as the fake's
 * own, a HOME of its own, `bin/claude` (the [FakeClaude.launcher]), the gates folder for `wait` steps and the scenario
 * file. Every process it starts writes into [log] and is ended by [stopAll]. Nothing here reads or writes the real
 * Claude home, since the environment refuses it.
 */
class FakeClaudeRig(val root: Path, private val python: Path) {
    val claudeHome: Path = folder("claude-home")
    val userHome: Path = folder("home")
    val bin: Path = folder("bin")
    val gates: Path = folder("gates")
    val scenarioFile: Path = root.resolve("scenario.json")
    val log: Path = root.resolve("terminal.log")
    private val started = mutableListOf<Process>()

    init {
        Files.writeString(claudeHome.resolve(FakeClaude.MARKER), "")
        val launcher = bin.resolve("claude")
        Files.writeString(launcher, FakeClaude.launcher(python, FakeClaude.script))
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.writeString(log, "")
    }

    private fun folder(name: String): Path = Files.createDirectories(root.resolve(name)).toRealPath()

    /** The scenario the next start of the fake follows. */
    fun scenario(json: String) {
        Files.writeString(scenarioFile, json)
    }

    /** Lets a fake that waits at `{"wait": "<gate>"}` go on. It takes the gate away again. */
    fun open(gate: String) {
        Files.writeString(gates.resolve(gate), "")
    }

    fun statusFile(pid: Long): Path = claudeHome.resolve("sessions").resolve("$pid.json")

    fun logText(): String = Files.readString(log)

    fun environment(extra: Map<String, String> = emptyMap()): Map<String, String> =
        FakeClaude.environment(
            claudeHome,
            userHome,
            bin,
            ceiling = root.toRealPath(),
            realUserHome = realUserHome(),
            extra = mapOf(FakeClaude.SCENARIO to scenarioFile.toString(), FakeClaude.GATES to gates.toString()) + extra,
        )

    /** The fake started on its own, as `claude [args]` in [cwd]. */
    fun claude(cwd: Path, vararg args: String, env: Map<String, String> = environment()): Process =
        start(listOf(bin.resolve("claude").toString()) + args, cwd, env)

    /**
     * A shell in [cwd] that reads its commands from its stdin, as a terminal's shell reads what is typed, with the fake
     * first on its PATH. It starts with SIGHUP at its default, as in a terminal, whatever the build was started with.
     */
    fun shell(cwd: Path): Process =
        start(listOf(python.toString(), "-c", "import os, signal; signal.signal(signal.SIGHUP, signal.SIG_DFL); os.execv('${FakeClaude.SHELL}', ['sh'])"), cwd, environment())

    private fun start(command: List<String>, cwd: Path, env: Map<String, String>): Process {
        val builder = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        builder.environment().clear()
        builder.environment().putAll(env)
        return builder.start().also { synchronized(started) { started += it } }
    }

    /**
     * Ends every process this rig started and what they started in turn, then every fake a status file in its Claude home
     * still names, one whose shell went say, which no longer descends from anything here. Such a fake gets SIGTERM, so
     * it takes its status file along, and is killed when it does not end within five seconds.
     */
    fun stopAll() {
        val all = synchronized(started) { started.toList() }
        for (process in all.asReversed()) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        for (fake in filedFakes()) {
            fake.destroy()
            try {
                fake.onExit().get(5, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                fake.destroyForcibly()
            } catch (e: ExecutionException) {
                fake.destroyForcibly()
            }
        }
    }

    /** The live processes the status files in this rig's Claude home name, each only while it started when its file says. */
    private fun filedFakes(): List<ProcessHandle> {
        val sessions = claudeHome.resolve("sessions")
        val files = try {
            Files.list(sessions).use { s -> s.filter { it.name.endsWith(".json") }.toList() }
        } catch (e: IOException) {
            return emptyList()
        }
        val self = ProcessHandle.current().pid()
        return files.mapNotNull { file ->
            val text = try {
                Files.readString(file)
            } catch (e: IOException) {
                return@mapNotNull null
            }
            val filed = (SessionFileParser.parse(text) as? SessionFileParse.Ok)?.file ?: return@mapNotNull null
            val startedAt = filed.startedAt ?: return@mapNotNull null
            val handle = ProcessHandle.of(filed.pid).orElse(null)?.takeIf { it.isAlive && it.pid() != self } ?: return@mapNotNull null
            val start = handle.info().startInstant().orElse(null) ?: return@mapNotNull null
            handle.takeIf { FakeClaude.isFiledProcess(startedAt, start.toEpochMilli()) }
        }
    }

    private fun realUserHome(): Path {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        return try {
            home.toRealPath()
        } catch (e: IOException) {
            home
        }
    }
}

/**
 * Calls [probe] every [intervalMillis] until it gives a value, and fails after [timeoutMillis] with [what] and
 * [context], the terminal log say. A wait with a deadline, never a fixed sleep that decides a test.
 */
fun <T : Any> awaitValue(what: String, timeoutMillis: Long = 15_000, intervalMillis: Long = 20, context: () -> String = { "" }, probe: () -> T?): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (true) {
        probe()?.let { return it }
        if (System.nanoTime() > deadline) throw AssertionError("timed out after $timeoutMillis ms waiting for $what\n${context()}")
        Thread.sleep(intervalMillis)
    }
}
