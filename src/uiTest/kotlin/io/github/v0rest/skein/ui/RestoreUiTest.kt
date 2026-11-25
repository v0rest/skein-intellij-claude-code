package io.github.v0rest.skein.ui

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.WaitForConditionTimeoutException
import com.intellij.remoterobot.utils.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Duration

/**
 * Threads brought back when their project opens again, in a real IDE with the user's real `claude` (a few tokens a
 * thread). Skein moves the restored tabs into the editor one at a time, and the one it leaves in front - the first of the
 * saved threads, with no editor of the user's to come back - starts by itself once the user has it before them a
 * moment, with no click - the others wait to be opened.
 */
class RestoreUiTest {
    private companion object {
        /** A turn of a few tokens, so each session has a conversation for `claude --resume` to go on with. */
        const val PROMPT = "Reply with the single word ok."

        /** What Skein logs for a move into the editor that went wrong, whatever it says after. */
        const val MOVE_FAILED = "could not move the terminal tab"
    }

    private val demo = DemoProject()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(demo).around(ScreenshotOnFailure { demo.name })

    private val repo get() = demo.repo
    private val project get() = demo.name!!

    /**
     * Three threads that each had a turn, the project closed the way File | Close Project closes it and opened again
     * from inside the IDE. From the reopen on the test moves no focus and the IDE never turns active again - Skein starts
     * the thread in view on either, as it did before the start in view existed. With no click, the first saved thread,
     * the one left in front, leaves "not started" within about 30 s, and a new claude comes up on its session - the step
     * a run against 1.5.3 fails at, whatever another project open in the IDE starts meanwhile. Then that thread is the
     * one in front, it is the only claude on this project's sessions, the other two stay not started, and the IDE's log
     * has no failed move from the reopen.
     */
    @Test
    fun theThreadLeftInFrontResumesWithNoClickAndTheOthersWait() {
        // which build runs there, for the log - a control run against 1.5.3 must fail at the start with no click
        val build = runCatching { RemoteIde.skeinBuild() }.getOrElse { "a Skein build it could not tell ($it)" }
        println("RestoreUiTest: the IDE under test runs $build")
        val frame = RemoteIde.frame(project)
        trustTheFolder(frame)
        val started = mutableListOf<Pair<Long, String>>()
        var before: Set<Long>? = null
        try {
            repeat(3) { startAThreadWithATurn(started) }
            assertEquals(3, RemoteIde.threadRows(project))
            // a plain editor left open would come back in front of the threads, and then none of them is in view
            RemoteIde.closeEditorsButThreads(project)
            // a finished turn nobody looked at puts its thread first in the saved list - seen, the list keeps the order the
            // threads were made in, which is the order the restore opens them in
            seeEveryTurn()
            val saved = RemoteIde.threadsOf(project).map { it.split('|') }
            assertEquals(3, saved.size)
            val sessions = saved.associate { it[0] to it[1] }
            assertTrue("a thread without a session: $sessions", sessions.values.none { it == "null" || it.isEmpty() })
            val first = saved.first()[0]
            inFront()

            RemoteIde.closeAndDispose(project)
            for ((pid, _) in started) {
                waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { !RemoteIde.alive(pid) }
                waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }
            }
            val claudesBefore = RemoteIde.claudePids()
            before = claudesBefore
            val log = RemoteIde.logLength()
            RemoteIde.countActivations()

            RemoteIde.open(repo)
            // no focus moves from here on - the frame the reopen made has to be in front by itself
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the three threads back in the reopened project") { RemoteIde.threadsOf(project).size == 3 }
            try {
                waitFor(Duration.ofSeconds(15), Duration.ofMillis(250), "$project in front with the IDE active") { RemoteIde.inFront() == project }
            } catch (e: WaitForConditionTimeoutException) {
                throw AssertionError("the reopened $project is not in front by itself ('${RemoteIde.inFront()}' is)", e)
            }

            // Skein's own decision first - the first saved thread leaves "not started" with no click, as a click would start
            // it, and a claude of another project open in this IDE cannot pass this
            fun seen() = "the IDE runs $build; in front: '${RemoteIde.inFront()}', thread in front: '${RemoteIde.threadInFront(project)}', " +
                "threads: ${RemoteIde.threadsOf(project)}"
            try {
                waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the first saved thread $first started with no click") {
                    stateOf(first).let { it != null && it != "NotStarted" && !it.startsWith("RunningElsewhere") }
                }
            } catch (e: WaitForConditionTimeoutException) {
                throw AssertionError("the first saved thread $first did not start with no click within 30 s; ${seen()}", e)
            }
            // and the claude it started is that thread's - a new one whose status file holds its session, the one saved or
            // the one its row shows by now, since a resume may come back with another id
            var resumed = emptyMap<Long, String>()
            try {
                waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "a new claude on the session of thread $first") {
                    resumed = claudesOn(claudesBefore, setOf(sessions.getValue(first)) + sessionsOf(listOf(first)))
                    resumed.isNotEmpty()
                }
            } catch (e: WaitForConditionTimeoutException) {
                throw AssertionError("no new claude on the session of thread $first within 90 s (new claudes: ${RemoteIde.claudePids() - claudesBefore}); ${seen()}", e)
            }
            for (each in resumed.keys) started += each to RemoteIde.startOf(each)
            assertEquals("more than one claude on the session of thread $first: $resumed", 1, resumed.size)
            val pid = resumed.keys.single()
            assertEquals("the thread in front is not the first saved one of ${saved.map { it[0] }}", first, RemoteIde.threadInFront(project))

            // a few more of Skein's looks, and still nothing else of this project starts
            Thread.sleep(5_000)
            val ours = sessions.values.toSet() + sessionsOf(sessions.keys)
            assertEquals("another claude on this project's sessions", setOf(pid), claudesOn(claudesBefore, ours).keys)
            val others = RemoteIde.threadsOf(project).map { it.split('|') }.filter { it[0] != first }
            assertEquals(2, others.size)
            for (thread in others) assertEquals("thread ${thread[0]}", "NotStarted", thread[2])
            assertEquals("the IDE turned active again after the reopen, which starts the thread in view the old way", 0, RemoteIde.activations())
            assertEquals("the reopen logged a failed move", emptyList<String>(), RemoteIde.logSince(log).filter { it.contains(MOVE_FAILED) })
        } finally {
            // every claude this test started, by pid and start, so a pid reused since is left alone - and once the project
            // came back, every other claude under the IDE that came up since, since one without its status file yet cannot
            // be told apart (another project's goes too) - before the count goes, whose robot call may fail
            for ((pid, start) in started) RemoteIde.destroy(pid, start)
            before?.let { for (pid in RemoteIde.claudePids() - it) RemoteIde.destroy(pid, RemoteIde.startOf(pid)) }
            RemoteIde.stopCountingActivations()
        }
    }

    /** The state of thread [id] of the project as its Skein list holds it, or null when it has no such thread. */
    private fun stateOf(id: String): String? = RemoteIde.threadsOf(project).map { it.split('|') }.firstOrNull { it[0] == id }?.get(2)

    /** The session ids the rows of threads [ids] show now, a thread still without one left out. */
    private fun sessionsOf(ids: Collection<String>): Set<String> =
        RemoteIde.threadsOf(project).map { it.split('|') }.filter { it[0] in ids && it[1] != "null" && it[1].isNotEmpty() }.map { it[1] }.toSet()

    /**
     * The claudes under the IDE that came up since [before] whose status file holds one of [sessions], with that session -
     * a claude of another project, or of another session, never counts, and neither does one without a status file yet.
     */
    private fun claudesOn(before: Set<Long>, sessions: Set<String>): Map<Long, String> =
        (RemoteIde.claudePids() - before).associateWith { RemoteIde.sessionField(it, "sessionId") }.filterValues { it in sessions }

    /**
     * Selects each thread whose finished turn nobody saw yet, so every thread reads as seen - a thread waiting for the user
     * would sort first in the saved list.
     */
    private fun seeEveryTurn() {
        fun waiting() = RemoteIde.threadsOf(project).map { it.split('|') }.filter { it[2].startsWith("YourTurn") || it[2].startsWith("NeedsYou") }
        for (thread in waiting()) RemoteIde.selectThread(project, thread[0])
        waitFor(Duration.ofSeconds(15), Duration.ofMillis(250), "every turn seen") { waiting().isEmpty() }
    }

    /** The project's window in front with the IDE active, which is what "in view" needs - the switch is no click on a thread. */
    private fun inFront() {
        RemoteIde.focusProject(project)
        waitFor(Duration.ofSeconds(15), Duration.ofMillis(250), "$project in front with the IDE active") { RemoteIde.inFront() == project }
    }

    /**
     * A new thread, its claude idle, then [PROMPT] sent into its tab - the selected editor, as a new thread's tab is -
     * and its answer. The new claude goes into [started] as soon as it is there, so a wait that fails later still ends it.
     */
    private fun startAThreadWithATurn(started: MutableList<Pair<Long, String>>) {
        val before = RemoteIde.claudePids()
        RemoteIde.newThread(project)
        val pid = newClaude(before)
        started += pid to RemoteIde.startOf(pid)
        waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle") { RemoteIde.sessionField(pid, "status") == "idle" }
        val sessionId = RemoteIde.sessionField(pid, "sessionId")
        assertTrue("claude $pid has no session id", sessionId.isNotEmpty())
        val answersBefore = answers(sessionId)
        RemoteIde.sendToTheThreadsTerminal(project, PROMPT, selected = true)
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude's answer in session $sessionId") { answers(sessionId) > answersBefore }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle after its answer") { RemoteIde.sessionField(pid, "status") == "idle" }
    }

    private fun answers(sessionId: String): Int {
        val assistant = Regex("\"type\"\\s*:\\s*\"assistant\"")
        return RemoteIde.transcript(sessionId).count { assistant.containsMatchIn(it) }
    }

    private fun newClaude(before: Set<Long>): Long {
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        return started.single()
    }

    /** A first thread whose trust question gets the Enter a person would give it, closed again, the way ThreadUiTest does it. */
    private fun trustTheFolder(frame: CommonContainerFixture) {
        val before = RemoteIde.claudePids()
        RemoteIde.startClaudeFromTheEmptyList(project)
        val pid = newClaude(before)
        RemoteIde.acceptTheTrustQuestion(project)
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        val tabs = frame.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='EditorTabLabel']"))
        val thread = tabs.first { tab -> tab.findAllText().none { it.text.contains("README") } }
        thread.find(ComponentFixture::class.java, byXpath(".//div[@class='InplaceButton']")).click()
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { pid !in RemoteIde.claudePids() }
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the trust thread's row gone") { RemoteIde.threadRows(project) == 0 }
    }
}
