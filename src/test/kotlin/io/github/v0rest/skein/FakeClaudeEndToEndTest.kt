package io.github.v0rest.skein

import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.HistoryScanner
import io.github.v0rest.skein.claude.SessionDirectoryWatcher
import io.github.v0rest.skein.claude.Transcripts
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.process.JdkProcessProbe
import io.github.v0rest.skein.process.LibcHangUp
import io.github.v0rest.skein.testutil.FakeClaude
import io.github.v0rest.skein.testutil.FakeClaudeRig
import io.github.v0rest.skein.testutil.RealGit
import io.github.v0rest.skein.testutil.ShellThreadTerminal
import io.github.v0rest.skein.testutil.awaitValue
import io.github.v0rest.skein.threads.CloseSequence
import io.github.v0rest.skein.threads.CoordinatorEvents
import io.github.v0rest.skein.threads.LaunchSlot
import io.github.v0rest.skein.threads.NotificationKind
import io.github.v0rest.skein.threads.PreviewCache
import io.github.v0rest.skein.threads.ResumeOutcome
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadCoordinator
import io.github.v0rest.skein.threads.ThreadMapper
import io.github.v0rest.skein.threads.ThreadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Skein against the fake claude (tools/fake-claude) the way it meets a real one. Skein types `claude` into a tab, a real
 * shell there (a child of the test JVM, which stands in for the IDE) starts the fake, and the real watcher, mapper and
 * coordinator find it through the process tree and follow the files it writes into a temporary Claude home. The
 * fake's wait steps open only once the test has seen the state before them, so no state slips by between two polls.
 * The watcher is polled here directly, since SessionMonitor, the app service around it, reads the user's own Claude
 * home. Skipped where python3 3.8 or newer or /bin/sh is missing, the worktree test also without git, and the close
 * test outside macOS and Linux.
 */
class FakeClaudeEndToEndTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val probe = JdkProcessProbe()
    private val mapper = ThreadMapper(probe)
    private val notifications = mutableListOf<NotificationKind>()
    private var missingStatusFiles = false
    private var n = 0

    private lateinit var rig: FakeClaudeRig
    private lateinit var home: ClaudeHome
    private lateinit var watcher: SessionDirectoryWatcher
    private lateinit var terminal: ShellThreadTerminal
    private lateinit var coordinator: ThreadCoordinator
    private lateinit var previews: PreviewCache

    private val events = object : CoordinatorEvents {
        override fun notify(thread: SkeinThread, kind: NotificationKind) {
            notifications += kind
        }

        override fun viewed(threadId: String) = Unit

        override fun closed(thread: SkeinThread) = Unit

        override fun leftNeedsYou(threadId: String) = Unit

        override fun missingStatusFiles() {
            missingStatusFiles = true
        }
    }

    @Before
    fun aRigAndSkein() {
        rig = FakeClaudeRig(tmp.root.toPath(), FakeClaude.requireRunnable())
        home = ClaudeHome(rig.claudeHome)
        watcher = SessionDirectoryWatcher(home, probe)
        terminal = ShellThreadTerminal(rig)
        // the IDE's child processes right before Skein types, as the real terminal lists them, so a Close can prove the shell
        terminal.fake.shellsNow = { mapper.ideChildList() }
        coordinator = ThreadCoordinator(terminal, mapper, LaunchSlot(), events) { "t${++n}" }
        // the model, context and preview of a row, read from the transcript as ThreadService reads it
        previews = PreviewCache(
            read = { thread -> thread.sessionId?.let { home.stamp(Transcripts.path(home, thread.folder, thread.sessionCwd, it)) } },
            tail = { thread -> thread.sessionId?.let { Transcripts.tailLines(home, thread.folder, thread.sessionCwd, it) }.orEmpty() },
            now = System::currentTimeMillis,
        )
    }

    @After
    fun stop() {
        if (::coordinator.isInitialized) coordinator.dispose()
        if (::rig.isInitialized) rig.stopAll()
    }

    private fun now(): Long = System.currentTimeMillis()

    /** One tick of ThreadService, a poll of the session directory handed to the coordinator, nobody looking at a tab. */
    private fun tick() = coordinator.onSnapshot(watcher.poll(), viewed = { false }, now = now())

    private fun await(id: String, what: String, check: (SkeinThread) -> Boolean): SkeinThread =
        awaitValue("thread $id $what", context = ::context) {
            tick()
            coordinator.threads.single { it.id == id }.takeIf(check)
        }

    private fun context(): String = "threads: ${coordinator.threads}\nterminal log:\n${rig.logText()}"

    private fun typed(): List<String> = terminal.fake.typed.map { it.second }

    private fun folder(name: String): Path = tmp.newFolder(name).toPath().toRealPath()

    @Test
    fun aThreadGoesWorkingNeedsYouFinishedAndEndsWithTheFake() {
        val project = folder("demo-shop")
        rig.scenario(
            """{"model": "claude-opus-5-5", "steps": [
                {"wait": "prompt"},
                {"prompt": "Make the demo-shop build green"},
                {"status": "busy"}, {"wait": "work"},
                {"toolUse": "Bash", "input": {"command": "./gradlew test"}},
                {"status": "waiting", "waitingFor": "permission prompt"}, {"wait": "permission"},
                {"toolResult": "BUILD SUCCESSFUL"},
                {"status": "busy"},
                {"answer": "The build is green again. One flaky test is fixed.",
                 "usage": {"input_tokens": 8, "cache_creation_input_tokens": 1500, "cache_read_input_tokens": 52000, "output_tokens": 640}},
                {"aiTitle": "Green build"},
                {"status": "idle"}, {"wait": "exit"}
            ]}""",
        )
        val id = coordinator.newThread(project.toString(), now())
        val shell = terminal.shellOf(id)

        val idle = await(id, "idle once claude is up") { it.state == ThreadState.Idle }
        val claude = watcher.poll().sessions.values.single()
        assertEquals("the fake runs in the tab's shell", shell.pid(), probe.parent(claude.pid))
        assertEquals("the tab's shell is the thread's anchor", shell.pid(), idle.anchor)
        assertEquals(claude.sessionId, idle.sessionId)
        assertEquals(project.toString(), idle.sessionCwd)

        rig.open("prompt")
        await(id, "working") { it.state == ThreadState.Working }
        rig.open("work")
        val waiting = await(id, "needing a permission") { it.state is ThreadState.NeedsYou }
        assertEquals(WaitReason.PERMISSION, (waiting.state as ThreadState.NeedsYou).reason)
        assertEquals("Bash: ./gradlew test", previews.refresh(coordinator.threads).lines[id])

        rig.open("permission")
        val finished = await(id, "finished its turn") { it.state is ThreadState.YourTurn }
        val row = previews.refresh(coordinator.threads)
        assertEquals("The build is green again.", row.lines[id])
        val context = row.contexts.getValue(id)
        assertEquals("claude-opus-5-5", context.model)
        assertEquals(53_508L, context.tokens)
        assertEquals("Opus · 53k", context.label)
        assertEquals(listOf(NotificationKind.NEEDS_YOU, NotificationKind.TURN_FINISHED), notifications)

        rig.open("exit")
        await(id, "exited with the fake") { it.state == ThreadState.Exited }
        assertFalse("the fake took its status file along", Files.exists(rig.statusFile(claude.pid)))
        assertTrue("the shell outlives claude, as in a terminal", shell.isAlive)
        assertFalse(missingStatusFiles)

        // History lists the session under its title, and resuming it starts the fake again in the same tab
        val entry = HistoryScanner(home).scan(listOf(project.toString())).entries.single()
        assertEquals(finished.sessionId, entry.sessionId)
        assertEquals("Green build", entry.title)
        rig.scenario("""{"steps": [{"wait": "bye"}]}""")
        assertEquals(ResumeOutcome.Selected(id), coordinator.resume(entry, watcher.poll(), now()))
        val resumed = await(id, "idle again after --resume") { it.state == ThreadState.Idle }
        assertEquals(entry.sessionId, resumed.sessionId)
        assertEquals(shell.pid(), resumed.anchor)
        rig.open("bye")
        await(id, "exited again") { it.state == ThreadState.Exited }

        assertEquals(listOf("claude", "claude --resume ${entry.sessionId}"), typed())
        terminal.endInput(id)
        assertTrue("the shell ends with its input", shell.waitFor(10, TimeUnit.SECONDS))
    }

    @Test
    fun aWorktreeThreadFindsTheFakeInItsWorktree() {
        val git = RealGit(tmp)
        assumeTrue("git is not installed", git.available)
        val repo = git.repository("acme").toRealPath()
        rig.scenario("""{"steps": [{"prompt": "Try the new acme parser"}, {"wait": "exit"}]}""")
        val id = coordinator.newWorktreeThread(repo.toString(), "task-1", now())

        val thread = await(id, "idle in its worktree") { it.state == ThreadState.Idle }
        assertEquals(repo.resolve(".claude/worktrees/task-1").toString(), thread.sessionCwd)
        assertEquals("task-1", thread.worktree)
        assertEquals(listOf("claude --worktree task-1"), typed())
        assertEquals("task-1", HistoryScanner(home).scan(listOf(repo.toString())).entries.single().worktree)

        rig.open("exit")
        await(id, "exited") { it.state == ThreadState.Exited }
    }

    /**
     * Skein's own Close, with real signals. The coordinator proves the tab's shell and names the fake, the close
     * sequence hangs up the fake, which takes its status file along, then the shell, and only then closes the tab.
     */
    @Test
    fun closeHangsUpTheFakeThenItsShellThenClosesTheTab() {
        val os = System.getProperty("os.name").lowercase()
        assumeTrue("signals are sent on macOS and Linux only", os.contains("mac") || os.contains("linux"))
        val project = folder("demo-shop")
        rig.scenario("""{"steps": [{"wait": "never"}]}""")
        val id = coordinator.newThread(project.toString(), now())
        val shell = terminal.shellOf(id)
        await(id, "idle") { it.state == ThreadState.Idle }
        val claude = watcher.poll().sessions.values.single()

        val outcome = coordinator.close(listOf(id), confirmed = emptySet(), snapshot = watcher.poll())
        assertTrue(outcome.leftOpen.isEmpty())
        val ending = outcome.closing.single()
        assertNull(ending.doubt)
        assertEquals(shell.pid(), ending.shell?.pid)
        assertEquals(claude.pid, ending.claude?.pid)

        val logged = mutableListOf<String>()
        runBlocking { CloseSequence(LibcHangUp(), terminal, mapper, timeoutMillis = 10_000) { logged += it }.run(outcome.closing) }
        assertEquals(emptyList<String>(), logged)
        assertFalse("the fake ended", probe.isAlive(claude.pid))
        assertFalse("and took its status file along", Files.exists(rig.statusFile(claude.pid)))
        assertTrue(shell.waitFor(5, TimeUnit.SECONDS))
        assertEquals("the shell ended on its SIGHUP", 128 + 1, shell.exitValue())
        assertEquals(listOf(listOf(id)), terminal.fake.closed)

        coordinator.onTabClosed(id)
        assertTrue(coordinator.threads.isEmpty())
        tick()
        assertTrue(watcher.poll().sessions.isEmpty())
    }
}
