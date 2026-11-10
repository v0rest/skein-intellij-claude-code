package io.github.v0rest.skein

import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.HistoryScanner
import io.github.v0rest.skein.claude.SessionDirectoryWatcher
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.terminal.ClaudeCommand
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.FakeThreadTerminal
import io.github.v0rest.skein.testutil.TreeDigest
import io.github.v0rest.skein.threads.CoordinatorEvents
import io.github.v0rest.skein.threads.LaunchSlot
import io.github.v0rest.skein.threads.NotificationKind
import io.github.v0rest.skein.threads.ResumeOutcome
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadCoordinator
import io.github.v0rest.skein.threads.ThreadMapper
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

/**
 * The design's read-only promise, end to end: a whole thread lifecycle (start, status changes, /clear, history,
 * resume, exit, restore, close) against a copy of a Claude home, with the real ClaudeHome, watcher and history
 * scanner. Only the test itself, standing in for `claude`, writes there; Skein must leave every other byte alone.
 */
class ReadOnlyLifecycleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val old = "11111111-1111-4111-8111-111111111111"
    private val first = "22222222-2222-4222-8222-222222222222"
    private val cleared = "33333333-3333-4333-8333-333333333333"

    @Test
    fun aFullLifecycleChangesNothingUnderTheClaudeHome() {
        val root = tmp.root.toPath().resolve(".claude")
        copyFixture(root)
        val before = TreeDigest.of(root)
        assertTrue("fixture copied", before.keys.containsAll(listOf("settings.json", "sessions/999.json", "projects/-w-p/$old.jsonl")))

        val written = mutableSetOf<String>()
        fun claudeWrites(relative: String, content: String) {
            val path = root.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
            written += relative
        }

        val home = ClaudeHome(root)
        val probe = FakeProcessProbe(current = 1).apply { add(1, parent = null, start = Instant.EPOCH) }
        var nextShell = 10L
        val spawnShell: (FakeThreadTerminal.Tab) -> Unit = { probe.add(nextShell++, parent = 1, start = Instant.EPOCH) }
        val watcher = SessionDirectoryWatcher(home, probe)
        val notifications = mutableListOf<NotificationKind>()
        val events = object : CoordinatorEvents {
            override fun notify(thread: SkeinThread, kind: NotificationKind) {
                notifications += kind
            }

            override fun viewed(threadId: String) = Unit

            override fun closed(thread: SkeinThread) = Unit

            override fun leftNeedsYou(threadId: String) = Unit

            override fun missingStatusFiles() = Unit
        }
        val terminal = FakeThreadTerminal().apply { onOpen = spawnShell }
        val slot = LaunchSlot()
        var n = 0
        val coordinator = ThreadCoordinator(terminal, ThreadMapper(probe), slot, events) { "t${++n}" }
        fun step(now: Long) = coordinator.onSnapshot(watcher.poll(), viewed = { false }, now = now)
        fun status(status: String, sessionId: String, waitingFor: String? = null) =
            """{"pid":100,"sessionId":"$sessionId","cwd":"/w/p","name":"lifecycle","status":"$status",""" +
                (waitingFor?.let { "\"waitingFor\":\"$it\"," } ?: "") + """"startedAt":1500}"""

        // a new thread: Skein types claude, claude writes its status file
        coordinator.newThread("/w/p", now = 1_000)
        probe.add(100, parent = 10, start = Instant.ofEpochMilli(1_400))
        claudeWrites("sessions/100.json", status("idle", first))
        step(2_000)
        assertEquals(ThreadState.Idle, coordinator.threads.single().state)

        claudeWrites("sessions/100.json", status("busy", first))
        step(3_000)
        claudeWrites("sessions/100.json", status("waiting", first, waitingFor = "permission prompt"))
        step(4_000)
        assertEquals(ThreadState.NeedsYou(WaitReason.PERMISSION, 4_000), coordinator.threads.single().state)
        claudeWrites("sessions/100.json", status("idle", first))
        step(5_000)
        assertEquals(ThreadState.YourTurn(5_000), coordinator.threads.single().state)
        assertEquals(listOf(NotificationKind.NEEDS_YOU, NotificationKind.TURN_FINISHED), notifications)

        // /clear: the same process now carries a new session id, and claude writes that session's transcript
        claudeWrites("sessions/100.json", status("idle", cleared))
        claudeWrites("projects/-w-p/$cleared.jsonl", """{"type":"user","cwd":"/w/p","sessionId":"$cleared","message":{"role":"user","content":"x"}}""" + "\n")
        step(6_000)
        assertEquals(cleared, coordinator.threads.single().sessionId)

        // history lists the old session and the live one
        val history = HistoryScanner(home).scan(listOf("/w/p")).entries
        assertEquals(setOf(old, cleared), history.map { it.sessionId }.toSet())
        assertEquals("old work", history.single { it.sessionId == old }.title)

        // resuming the old session opens a second thread with claude --resume
        val outcome = coordinator.resume(history.single { it.sessionId == old }, watcher.poll(), now = 7_000)
        assertTrue(outcome is ResumeOutcome.Opened)

        // claude exits and removes its status file
        probe.kill(100)
        Files.delete(root.resolve("sessions/100.json"))
        step(8_000)
        assertEquals(ThreadState.Exited, coordinator.threads.single { it.id == "t1" }.state)

        // an IDE restart: a new window restores the saved threads and resumes one only when it is opened
        val saved = coordinator.threads
        val restartedTerminal = FakeThreadTerminal().apply { onOpen = spawnShell }
        val restarted = ThreadCoordinator(restartedTerminal, ThreadMapper(probe), LaunchSlot(), events) { "r${++n}" }
        restarted.restore(saved, watcher.poll(), now = 9_000)
        assertTrue(restarted.threads.all { it.state == ThreadState.NotStarted })
        restarted.onTabSelected("t1", watcher.poll(), now = 10_000)
        restarted.onTabClosed("t1")
        coordinator.onTabClosed("t1")
        coordinator.dispose()

        // only claude and claude --resume <uuid> were ever typed
        val typed = (terminal.typed + restartedTerminal.typed).map { it.second }
        assertEquals(listOf("claude", "claude --resume $old", "claude --resume $cleared"), typed)
        assertTrue(typed.all(ClaudeCommand::isAllowed))

        // and every byte Skein could have touched is unchanged
        val after = TreeDigest.of(root)
        assertEquals(before, after.filterKeys { key -> key !in written && written.none { key.startsWith("$it/") } })
        assertTrue("nothing the fixture held was removed", after.keys.containsAll(before.keys))
    }

    private fun copyFixture(target: Path) {
        val source = Path.of(requireNotNull(javaClass.getResource("/fixtures/claude-home")) { "fixture missing" }.toURI())
        Files.walk(source).use { paths ->
            paths.asSequence().forEach { path ->
                val destination = target.resolve(path.relativeTo(source).toString())
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
            }
        }
    }
}
