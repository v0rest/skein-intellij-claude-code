package io.github.v0rest.skein

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.HistoryScanner
import io.github.v0rest.skein.claude.SessionDirectoryWatcher
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.claude.Slug
import io.github.v0rest.skein.claude.TranscriptTitle
import io.github.v0rest.skein.claude.Transcripts
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.claude.waitReasonOf
import io.github.v0rest.skein.process.JdkProcessProbe
import io.github.v0rest.skein.process.LibcHangUp
import io.github.v0rest.skein.terminal.ClaudeCommand
import io.github.v0rest.skein.testutil.FakeClaude
import io.github.v0rest.skein.testutil.FakeClaudeRig
import io.github.v0rest.skein.testutil.RealGit
import io.github.v0rest.skein.testutil.awaitValue
import io.github.v0rest.skein.threads.ContextUse
import io.github.v0rest.skein.threads.ThreadPreviews
import io.github.v0rest.skein.threads.ThreadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * The fake claude (tools/fake-claude) against Skein's own readers. Every status it writes is one the watcher takes for a
 * live session, every transcript line reads as Skein reads a real one (preview, model and context, title, History), it
 * never comes near the real Claude home or a folder not marked as its own, and it never outlives its test. Each test
 * runs the fake on its own, with a temporary Claude home, and is skipped where python3 3.8 or newer or /bin/sh is
 * missing.
 */
class FakeClaudeContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rig: FakeClaudeRig
    private lateinit var project: Path
    private lateinit var watcher: SessionDirectoryWatcher

    @Before
    fun aRig() {
        rig = FakeClaudeRig(tmp.root.toPath(), FakeClaude.requireRunnable())
        project = tmp.newFolder("demo-shop").toPath().toRealPath()
        watcher = SessionDirectoryWatcher(ClaudeHome(rig.claudeHome), JdkProcessProbe())
    }

    @After
    fun stop() {
        if (::rig.isInitialized) rig.stopAll()
    }

    private fun context(): String {
        val files = Files.walk(rig.claudeHome).use { s -> s.asSequence().map { rig.claudeHome.relativize(it).toString() }.sorted().toList() }
        return "terminal log:\n${rig.logText()}\nClaude home: $files"
    }

    /** The fake's session as Skein's watcher sees it (alive, started when the file says), once [check] holds. */
    private fun session(process: Process, what: String, check: (SessionFile) -> Boolean = { true }): SessionFile =
        awaitValue(what, context = ::context) { watcher.poll().sessions[process.pid()]?.takeIf(check) }

    private fun exitOf(process: Process): Int {
        // the log is read once the wait is over, not before it, or the message would show it as it was at the start
        if (!process.waitFor(15, TimeUnit.SECONDS)) throw AssertionError("the fake did not exit\n${context()}")
        return process.exitValue()
    }

    private fun tail(file: SessionFile): List<String> = Transcripts.tailLines(ClaudeHome(rig.claudeHome), project.toString(), file.cwd, file.sessionId!!)

    /** Nothing but the marker the rig put there, so the fake wrote nothing. */
    private fun claudeHomeHoldsOnlyTheMarker(): Boolean =
        Files.list(rig.claudeHome).use { s -> s.map { it.name }.toList() } == listOf(FakeClaude.MARKER)

    /** The fake refuses with exit code 2, and for the reason the case is about. */
    private fun refused(case: String, reason: String, start: () -> Process) {
        val before = rig.logText().length
        assertEquals(case, 2, exitOf(start()))
        val said = rig.logText().substring(before)
        assertTrue("$case: $said", said.contains(reason))
    }

    @Test
    fun everyStatusTheFakeWritesIsOneSkeinReads() {
        rig.scenario(
            """{"steps": [
                {"wait": "a"}, {"status": "busy"}, {"wait": "b"}, {"status": "shell"}, {"wait": "c"},
                {"status": "waiting", "waitingFor": "permission prompt"}, {"wait": "d"},
                {"status": "waiting", "waitingFor": "input needed"}, {"wait": "e"}, {"status": "idle"}, {"wait": "f"}
            ]}""",
        )
        val before = System.currentTimeMillis()
        val fake = rig.claude(project)
        val first = session(fake, "the first status") { it.activity == ClaudeActivity.IDLE }
        assertEquals(fake.pid(), first.pid)
        assertEquals("interactive", first.kind)
        assertTrue(first.sessionId, ClaudeCommand.isSessionId(first.sessionId))
        assertEquals(project.toString(), first.cwd)
        assertTrue("started when it ran", first.startedAt!! >= before && first.startedAt!! <= System.currentTimeMillis())
        assertNotNull(first.statusUpdatedAt)
        assertNotNull(first.version)
        assertFalse(first.nameDerived)

        rig.open("a")
        session(fake, "busy") { it.activity == ClaudeActivity.BUSY }
        rig.open("b")
        session(fake, "a background shell") { it.activity == ClaudeActivity.BACKGROUND }
        rig.open("c")
        session(fake, "a permission prompt") { it.activity == ClaudeActivity.WAITING && waitReasonOf(it.waitingFor) == WaitReason.PERMISSION }
        rig.open("d")
        val question = session(fake, "a question") { it.activity == ClaudeActivity.WAITING && waitReasonOf(it.waitingFor) == WaitReason.QUESTION }
        assertEquals(first.sessionId, question.sessionId)
        rig.open("e")
        session(fake, "idle again") { it.activity == ClaudeActivity.IDLE }
        rig.open("f")

        assertEquals(0, exitOf(fake))
        assertFalse("the status file goes with a normal exit", Files.exists(rig.statusFile(fake.pid())))
        assertTrue(watcher.poll().sessions.isEmpty())
    }

    @Test
    fun theTranscriptReadsAsSkeinReadsARealOne() {
        rig.scenario(
            """{"model": "claude-opus-5-5", "steps": [
                {"prompt": "Make the checkout of demo-shop faster"},
                {"status": "busy"},
                {"toolUse": "Bash", "input": {"command": "./gradlew test --tests Checkout\nsecond line"}},
                {"status": "waiting", "waitingFor": "permission prompt"}, {"wait": "permission"},
                {"toolResult": "BUILD SUCCESSFUL"},
                {"toolUse": "AskUserQuestion", "input": {"questions": [{"question": "Cache the totals in Redis?"}]}},
                {"status": "waiting", "waitingFor": "input needed"}, {"wait": "question"},
                {"toolResult": "Yes"},
                {"status": "busy"},
                {"answer": "The checkout now caches its totals. Tests pass.",
                 "usage": {"input_tokens": 12, "cache_creation_input_tokens": 2000, "cache_read_input_tokens": 30000, "output_tokens": 900}},
                {"lastPrompt": "Make the checkout of demo-shop faster"},
                {"aiTitle": "Faster checkout"},
                {"status": "idle"}, {"wait": "titles"},
                {"customTitle": "checkout speed"}, {"wait": "compact"},
                {"compact": true}, {"wait": "end"}
            ]}""",
        )
        val before = System.currentTimeMillis()
        val fake = rig.claude(project)

        val permission = session(fake, "the permission prompt") { it.activity == ClaudeActivity.WAITING }
        assertEquals("Bash: ./gradlew test --tests Checkout", ThreadPreviews.previewOf(ThreadState.NeedsYou(WaitReason.PERMISSION, 0), tail(permission)))
        rig.open("permission")

        val question = session(fake, "the question") { it.activity == ClaudeActivity.WAITING && waitReasonOf(it.waitingFor) == WaitReason.QUESTION }
        assertEquals("Cache the totals in Redis?", ThreadPreviews.previewOf(ThreadState.NeedsYou(WaitReason.QUESTION, 0), tail(question)))
        rig.open("question")

        val done = session(fake, "the finished turn") { it.activity == ClaudeActivity.IDLE }
        val lines = tail(done)
        assertEquals("The checkout now caches its totals.", ThreadPreviews.previewOf(ThreadState.YourTurn(0), lines))
        val use = ContextUse.fromTail(lines)!!
        assertEquals("claude-opus-5-5", use.model)
        assertEquals(32_012L, use.tokens)
        assertTrue("the answer's time parses: ${use.at}", use.at != null && use.at!! >= before - 1_000 && use.at!! <= System.currentTimeMillis() + 1_000)
        assertEquals("Faster checkout", TranscriptTitle.fromLines(lines))

        // History lists it, so it is not taken for a scripted run, under the folder it ran in
        val history = HistoryScanner(ClaudeHome(rig.claudeHome))
        val entry = history.scan(listOf(project.toString())).entries.single()
        assertEquals(done.sessionId, entry.sessionId)
        assertEquals(project.toString(), entry.cwd)
        assertEquals("Faster checkout", entry.title)
        assertNull(entry.worktree)
        assertEquals(rig.claudeHome.resolve("projects/${Slug.of(project.toString())}/${done.sessionId}.jsonl"), entry.transcript)

        rig.open("titles")
        awaitValue("the /rename title", context = ::context) { history.scan(listOf(project.toString())).entries.single().title.takeIf { it == "checkout speed" } }
        rig.open("compact")
        awaitValue("the compaction", context = ::context) { ContextUse.scan(tail(done)).takeIf { it == ContextUse.Scan.Reset } }
        rig.open("end")
        assertEquals(0, exitOf(fake))
    }

    @Test
    fun clearAndRenameKeepTheProcess() {
        rig.scenario("""{"steps": [{"wait": "a"}, {"name": "checkout work", "nameSource": "user"}, {"wait": "b"}, {"clear": true}, {"wait": "c"}]}""")
        val fake = rig.claude(project)
        val first = session(fake, "the start")
        assertNull(first.name)
        rig.open("a")
        session(fake, "the name") { it.name == "checkout work" && !it.nameDerived }
        rig.open("b")
        val cleared = session(fake, "a new session after /clear") { it.sessionId != first.sessionId }
        assertTrue(ClaudeCommand.isSessionId(cleared.sessionId))
        assertEquals(fake.pid(), cleared.pid)
        rig.open("c")
        assertEquals(0, exitOf(fake))
    }

    /** SIGTERM and SIGHUP, the hangup a closing terminal and Skein's Close send, take the status file along. */
    @Test
    fun aSignalTakesTheStatusFileAlong() {
        rig.scenario("""{"steps": [{"wait": "never"}]}""")
        val terminated = rig.claude(project)
        session(terminated, "the first fake")
        terminated.destroy()
        assertEquals("ended by SIGTERM", 128 + 15, exitOf(terminated))
        assertFalse(Files.exists(rig.statusFile(terminated.pid())))

        val hungUp = rig.claude(project)
        session(hungUp, "the second fake")
        assertTrue(LibcHangUp().hangUp(hungUp.pid()))
        assertEquals("ended by SIGHUP", 128 + 1, exitOf(hungUp))
        assertFalse(Files.exists(rig.statusFile(hungUp.pid())))
    }

    @Test
    fun theRealClaudeHomeStaysOutOfReach() {
        rig.scenario("""{"steps": []}""")
        val env = rig.environment()
        val itsDotClaude = rig.userHome.resolve(".claude")
        val notSet = "CLAUDE_CONFIG_DIR is not set"
        val theRealOne = "is, holds or lies in the real Claude home"
        refused("no CLAUDE_CONFIG_DIR", notSet) { rig.claude(project, env = env - "CLAUDE_CONFIG_DIR") }
        refused("an empty one", notSet) { rig.claude(project, env = env + ("CLAUDE_CONFIG_DIR" to " ")) }
        refused("a relative one", "must be an absolute path") { rig.claude(project, env = env + ("CLAUDE_CONFIG_DIR" to "claude-home")) }
        refused("~/.claude itself", theRealOne) { rig.claude(project, env = env + ("CLAUDE_CONFIG_DIR" to itsDotClaude.toString())) }
        refused("a folder inside ~/.claude", theRealOne) { rig.claude(project, env = env + ("CLAUDE_CONFIG_DIR" to itsDotClaude.resolve("sub").toString())) }
        refused("the folder that holds ~/.claude", theRealOne) { rig.claude(project, env = env + ("CLAUDE_CONFIG_DIR" to rig.userHome.toString())) }
        // a wait step removes the file it waits for, so gates in a Claude home could take settings.json away
        refused("gates inside the Claude home", "must lie outside the Claude home") {
            rig.claude(project, env = env + (FakeClaude.GATES to rig.claudeHome.resolve("gates").toString()))
        }
        // a worktree started in the home folder would land in its .claude
        refused("a worktree in ~/.claude", "would lie in the Claude home") { rig.claude(rig.userHome, "--worktree", "task-1") }
        assertFalse("nothing made in the home folder", Files.exists(itsDotClaude))
        assertTrue("nothing written to the Claude home", claudeHomeHoldsOnlyTheMarker())

        // the marker never makes ~/.claude the fake's own
        Files.createDirectories(itsDotClaude)
        Files.writeString(itsDotClaude.resolve(FakeClaude.MARKER), "")
        refused("~/.claude, even marked", theRealOne) { rig.claude(project, env = env + ("CLAUDE_CONFIG_DIR" to itsDotClaude.toString())) }
        assertEquals(listOf(FakeClaude.MARKER), Files.list(itsDotClaude).use { s -> s.map { it.name }.toList() })
    }

    /**
     * A Claude home the fake did not get from a test or the sandbox recipe, a CLAUDE_CONFIG_DIR exported in a shell's rc
     * files say, has no marker, and the fake writes nothing there.
     */
    @Test
    fun aClaudeHomeWithoutTheMarkerIsRefused() {
        rig.scenario("""{"steps": []}""")
        val unmarked = tmp.newFolder("custom-claude-home").toPath().toRealPath()
        refused("an unmarked Claude home", "has no ${FakeClaude.MARKER}") {
            rig.claude(project, env = rig.environment() + ("CLAUDE_CONFIG_DIR" to unmarked.toString()))
        }
        assertEquals(emptyList<Path>(), Files.list(unmarked).use { it.toList() })
    }

    /** A fake left waiting at a gate after its test is gone would wait forever, so it ends once its gates folder goes. */
    @Test
    fun aGatedFakeEndsWhenItsGatesGo() {
        rig.scenario("""{"steps": [{"wait": "never"}]}""")
        val fake = rig.claude(project)
        session(fake, "the waiting fake")
        Files.delete(rig.gates)
        assertEquals(1, exitOf(fake))
        assertFalse(Files.exists(rig.statusFile(fake.pid())))
        assertTrue(rig.logText(), rig.logText().contains("the gates folder"))
    }

    /** Its shell killed outright, the fake gets no signal, only a new parent, and ends on that. */
    @Test
    fun aGatedFakeEndsWhenItsShellIsGone() {
        rig.scenario("""{"steps": [{"wait": "never"}]}""")
        val shell = rig.shell(project)
        shell.outputStream.apply {
            write("claude\n".toByteArray())
            flush()
        }
        val file = awaitValue("the fake in the shell", context = ::context) { watcher.poll().sessions.values.singleOrNull() }
        val fake = ProcessHandle.of(file.pid).orElseThrow()
        assertEquals(shell.pid(), fake.parent().map { it.pid() }.orElse(null))
        shell.destroyForcibly()
        awaitValue("the orphaned fake's end", context = ::context) { (!fake.isAlive).takeIf { it } }
        assertFalse(Files.exists(rig.statusFile(file.pid)))
        assertTrue(rig.logText(), rig.logText().contains("its parent process"))
    }

    @Test
    fun aGatedFakeGivesUpAfterTheLongestWait() {
        rig.scenario("""{"steps": [{"wait": "never"}]}""")
        val fake = rig.claude(project, env = rig.environment(mapOf("FAKE_CLAUDE_GATE_TIMEOUT" to "0.5")))
        assertEquals(1, exitOf(fake))
        assertFalse(Files.exists(rig.statusFile(fake.pid())))
        assertTrue(rig.logText(), rig.logText().contains("waited 0.5 s for the gate never"))
        refused("a timeout that is no number of seconds", "FAKE_CLAUDE_GATE_TIMEOUT") {
            rig.claude(project, env = rig.environment(mapOf("FAKE_CLAUDE_GATE_TIMEOUT" to "soon")))
        }
    }

    /** A fake the rig did not start itself, as one whose shell went, is still stopped through the status file naming it. */
    @Test
    fun stopAllEndsAFakeThatOnlyItsStatusFileNames() {
        rig.scenario("""{"steps": [{"wait": "never"}]}""")
        val builder = ProcessBuilder(rig.bin.resolve("claude").toString())
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(rig.log.toFile()))
        builder.environment().clear()
        builder.environment().putAll(rig.environment())
        val untracked = builder.start()
        try {
            session(untracked, "the fake the rig did not start")
            rig.stopAll()
            if (!untracked.waitFor(10, TimeUnit.SECONDS)) throw AssertionError("stopAll left it running\n${context()}")
            assertFalse("it ended on SIGTERM and took its status file along", Files.exists(rig.statusFile(untracked.pid())))
        } finally {
            untracked.destroyForcibly()
        }
    }

    /** `--resume <id>` carries on the session in its transcript and goes back to the folder it ran in, a worktree say. */
    @Test
    fun resumeComesBackToTheSessionAndItsFolder() {
        val worktree = Files.createDirectories(project.resolve(".claude/worktrees/task-1"))
        rig.scenario("""{"steps": [{"prompt": "first part"}, {"aiTitle": "Checkout"}]}""")
        assertEquals(0, exitOf(rig.claude(worktree)))
        val transcript = Files.list(rig.claudeHome.resolve("projects/${Slug.of(worktree.toString())}")).use { it.toList() }.single()
        val id = transcript.name.removeSuffix(".jsonl")
        val linesBefore = Files.readAllLines(transcript).size

        rig.scenario("""{"steps": [{"wait": "r1"}, {"prompt": "second part"}, {"wait": "r2"}]}""")
        val resumed = rig.claude(project, "--resume", id)
        val file = session(resumed, "the resumed session")
        assertEquals(id, file.sessionId)
        assertEquals(worktree.toString(), file.cwd)
        rig.open("r1")
        awaitValue("the second prompt", context = ::context) { Files.readAllLines(transcript).size.takeIf { it == linesBefore + 1 } }
        assertTrue(Files.readAllLines(transcript).last().contains("second part"))
        rig.open("r2")
        assertEquals(0, exitOf(resumed))

        assertEquals("an unknown session", 1, exitOf(rig.claude(project, "--resume", "0123abcd-ef01-4345-8789-abcdef012345")))
        assertEquals("not a session id", 2, exitOf(rig.claude(project, "--resume", "last")))
        assertTrue(rig.logText().contains("No conversation found with session ID: 0123abcd-ef01-4345-8789-abcdef012345"))
        assertTrue("no status file left", Files.list(rig.claudeHome.resolve("sessions")).use { it.count() == 0L })
    }

    @Test
    fun worktreeStartsInTheWorktreeFolder() {
        rig.scenario("""{"steps": [{"wait": "end"}]}""")
        val fake = rig.claude(project, "--worktree", "task-1")
        val file = session(fake, "the worktree session")
        assertEquals(project.resolve(".claude/worktrees/task-1").toString(), file.cwd)
        rig.open("end")
        assertEquals(0, exitOf(fake))
        assertEquals("a name Claude Code would not take", 2, exitOf(rig.claude(project, "--worktree", "../escape")))
    }

    /** In a repository the fake makes a real git worktree on `worktree-<name>`, so the sandbox has one to show. */
    @Test
    fun worktreeInARepositoryIsAGitWorktree() {
        val git = RealGit(tmp)
        assumeTrue("git is not installed", git.available)
        val repo = git.repository("acme").toRealPath()
        rig.scenario("""{"steps": [{"wait": "end"}]}""")
        val fake = rig.claude(repo, "--worktree", "task-2")
        val file = session(fake, "the worktree session")
        val worktree = repo.resolve(".claude/worktrees/task-2")
        assertEquals(worktree.toString(), file.cwd)
        val listed = git.run(repo, "worktree", "list", "--porcelain")
        assertTrue(listed.stdout, listed.stdout.contains("worktree $worktree\n") && listed.stdout.contains("branch refs/heads/worktree-task-2"))
        rig.open("end")
        assertEquals(0, exitOf(fake))
    }

    @Test
    fun whatSkeinNeverTypesIsRefusedAndWritesNothing() {
        rig.scenario("""{"steps": [{"status": "busy"}]}""")
        for (args in listOf(listOf("--print", "hi"), listOf("-p"), listOf("--resume"), listOf("--worktree", "a", "--worktree", "b"))) {
            assertEquals(args.toString(), 2, exitOf(rig.claude(project, *args.toTypedArray())))
        }
        for (scenario in listOf("""{"steps": [{"dance": true}]}""", """{"steps": [{"status": "busy", "colour": "red"}]}""", """{"steps": [{"toolResult": "x"}]}""", "[", """{"steps": [{"wait": "../x"}]}""")) {
            rig.scenario(scenario)
            assertEquals(scenario, 2, exitOf(rig.claude(project)))
        }
        assertTrue("nothing written to the Claude home", claudeHomeHoldsOnlyTheMarker())
    }

    @Test
    fun theScenariosThatComeWithItAreValid() {
        // everything in the folder goes out with the repository, so a stray file fails here
        val shipped = Files.list(FakeClaude.scenarios).use { s -> s.sorted().toList() }
        assertTrue(shipped.map { it.name }.toString(), shipped.any { it.name == "default.json" })
        for (scenario in shipped) {
            assertTrue("${scenario.name} is no scenario file", scenario.name.endsWith(".json") && Files.isRegularFile(scenario))
            assertEquals(scenario.name, 0, exitOf(rig.claude(project, "--fake-check-scenario", scenario.toString())))
        }
        rig.scenario("""{"steps": [{"sleep": -1}]}""")
        assertEquals(2, exitOf(rig.claude(project, "--fake-check-scenario", rig.scenarioFile.toString())))
        assertTrue("checking writes nothing", claudeHomeHoldsOnlyTheMarker())
    }
}
