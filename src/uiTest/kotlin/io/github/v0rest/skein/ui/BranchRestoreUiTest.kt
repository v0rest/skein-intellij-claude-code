package io.github.v0rest.skein.ui

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.awt.Point
import java.time.Duration

/**
 * The IDE's "Restore workspace on branch switching" with a thread open, in a real IDE with the user's real `claude`. A
 * checkout through the IDE of a branch it saved a workspace for closes every editor without asking Skein, and Skein keeps
 * the thread through it - the same claude, its tab back in the editor and in front again, its row, and claude answering a
 * prompt after (a few tokens). The IDE's own clear of the workspace without a branch switch, what the restore balloon's
 * Rollback and Load Context run, ends claude cleanly first, leaves no claude behind, and brings the thread back as a
 * Resume of its session, which goes on with a second prompt in the same transcript (a few tokens more). And a thread tab
 * Skein marked for a branch switch that the user closes past the check, the way a middle click does, is ended and stays
 * closed.
 */
class BranchRestoreUiTest {
    private companion object {
        const val OTHER = "other"

        /** The prompt a test sends, which takes a few seconds, as RenameUiTest's does, so the turn is seen. */
        const val PROMPT = "write a 150-word paragraph about rivers"

        /** The second prompt, sent to the claude resumed after the fallback, words that are nowhere else in the transcript. */
        const val SECOND_PROMPT = "write a 150-word paragraph about mountain lakes"
    }

    private val demo = DemoProject()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(demo).around(ScreenshotOnFailure { demo.name })

    private val repo get() = demo.repo
    private val project get() = demo.name!!

    @Test
    fun aCheckoutThatRestoresTheWorkspaceKeepsTheThreadAndItsClaude() {
        val frame = RemoteIde.frame(project)
        trustTheFolder(frame)
        val (pid, sessionId) = startAnIdleThread()
        val start = RemoteIde.startOf(pid)
        try {
            assertTrue("the thread's tab is not in the editor", RemoteIde.terminalInTheEditor(project))
            RemoteIde.newBranch(repo, OTHER)
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "Git active in $project") { RemoteIde.gitActive(project) }
            RemoteIde.restoreWorkspaceOnBranchSwitch(project)

            // away and back - the IDE saves main's workspace on the way out and restores it on the way back
            checkout(OTHER)
            assertEquals("a branch without a saved workspace restored one", false, RemoteIde.workspaceRestored(project))
            checkout("main")
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the IDE's balloon about the restored workspace") { RemoteIde.workspaceRestored(project) }

            waitFor(Duration.ofSeconds(10), Duration.ofMillis(200), "the thread's tab back in the editor") { RemoteIde.terminalInTheEditor(project) }
            // it was in front before the checkout, so it is in front again
            waitFor(Duration.ofSeconds(10), Duration.ofMillis(200), "the thread's tab selected again") {
                RemoteIde.selectedEditorFile(project).contains("{TerminalViewVirtualFile}")
            }
            assertTrue("claude $pid did not live through the restore", RemoteIde.alive(pid) && pid in RemoteIde.claudePids())
            assertEquals(start, RemoteIde.startOf(pid))
            assertEquals(1, RemoteIde.threadRows(project))
            assertEquals(sessionId, RemoteIde.sessionField(pid, "sessionId"))

            sendOnePrompt(frame, pid, sessionId)
            assertEquals("", RemoteIde.gitStatus(repo).trim())
        } finally {
            RemoteIde.destroy(pid, start)
        }
    }

    @Test
    fun aClearedWorkspaceEndsClaudeAndBringsTheThreadBackAsAResume() {
        val frame = RemoteIde.frame(project)
        trustTheFolder(frame)
        val (pid, sessionId) = startAnIdleThread()
        val start = RemoteIde.startOf(pid)
        var resumed: Long? = null
        try {
            // a turn first, so the session has a conversation to go on with
            sendOnePrompt(frame, pid, sessionId)
            val before = RemoteIde.claudePids()
            RemoteIde.clearWorkspace(project)

            // ended cleanly while its tab was still open, so it is gone, not left running with nobody reading it
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { !RemoteIde.alive(pid) }
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }

            // the same thread in a new tab, with a new claude
            waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "a new claude for the thread") {
                resumed = (RemoteIde.claudePids() - before - pid).singleOrNull()
                resumed != null
            }
            val again = resumed!!
            assertEquals(1, RemoteIde.threadRows(project))
            assertTrue("the thread's tab is not back in the editor", RemoteIde.terminalInTheEditor(project))
            waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "the status file of claude $again") { again in RemoteIde.sessionPids() }
            waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $again idle") { RemoteIde.sessionField(again, "status") == "idle" }

            // a Resume, proved by the session itself - its second prompt and the answer land in the same transcript
            val answersBefore = answers(sessionId)
            sendOnePrompt(frame, again, sessionId, SECOND_PROMPT)
            assertTrue("the second prompt is not in the transcript of $sessionId", RemoteIde.transcript(sessionId).any { it.contains(SECOND_PROMPT) })
            assertTrue("no new answer in the transcript of $sessionId", answers(sessionId) > answersBefore)
            assertEquals("", RemoteIde.gitStatus(repo).trim())
        } finally {
            RemoteIde.destroy(pid, start)
            resumed?.let { RemoteIde.destroy(it, RemoteIde.startOf(it)) }
        }
    }

    /**
     * A thread tab that carries Skein's reopen mark for a branch switch and that the user closes past the close check, the
     * way a middle click on the tab does (`FileEditorManagerImpl.closeFile(file, window)`, no checks). The mark keeps its
     * terminal through that close, so Skein must end claude itself and let the thread go, never open the tab again - also
     * when the checkout says it is over while Skein still waits to tell the close from a drag, which must not make the
     * user's close the restore's. The branch switch is only announced, its end for a branch with no saved workspace, so
     * no checkout and no restore interferes.
     */
    @Test
    fun aMarkedTabClosedPastTheCheckIsEndedAndStaysClosed() {
        val frame = RemoteIde.frame(project)
        trustTheFolder(frame)
        val (pid, _) = startAnIdleThread()
        val start = RemoteIde.startOf(pid)
        try {
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "Git active in $project") { RemoteIde.gitActive(project) }
            val before = RemoteIde.claudePids()
            RemoteIde.announceBranchSwitch(project, "main")
            // Skein's listener, from its optional part, marked the tab - else this close would be an ordinary one
            waitFor(Duration.ofSeconds(10), Duration.ofMillis(200), "Skein's reopen mark on the thread's tab") { RemoteIde.threadTabMarkedToReopen(project) }
            RemoteIde.closeTheThreadsTabWithoutChecks(project)
            // the checkout is over a second later, well inside Skein's wait
            Thread.sleep(1_000)
            RemoteIde.announceBranchSwitchOver(project, "elsewhere")

            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { !RemoteIde.alive(pid) }
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the thread's row gone") { RemoteIde.threadRows(project) == 0 }
            // and it does not come back
            Thread.sleep(5_000)
            assertTrue("the thread's tab came back", !RemoteIde.terminalInTheEditor(project))
            assertEquals(0, RemoteIde.threadRows(project))
            assertEquals("a claude was started again", emptySet<Long>(), RemoteIde.claudePids() - before)
        } finally {
            RemoteIde.destroy(pid, start)
        }
    }

    /** A checkout through the IDE and the wait until git has that branch out and the IDE has had its look at it. */
    private fun checkout(branch: String) {
        RemoteIde.checkoutThroughTheIde(project, branch)
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "$branch checked out") { RemoteIde.currentBranch(repo) == branch }
        // the IDE tells its listeners once its own checkout is over, a moment after git
        Thread.sleep(3_000)
    }

    /** Starts a thread from the empty list in the trusted folder, waits until its claude is idle, and gives its pid and session. */
    private fun startAnIdleThread(): Pair<Long, String> {
        val before = RemoteIde.claudePids()
        RemoteIde.startClaudeFromTheEmptyList(project)
        val pid = newClaude(before)
        waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle") { RemoteIde.sessionField(pid, "status") == "idle" }
        val sessionId = RemoteIde.sessionField(pid, "sessionId")
        assertTrue("claude $pid has no session id", sessionId.isNotEmpty())
        return pid to sessionId
    }

    /** [prompt] and Enter into the thread's claude, then its answer and claude idle again. */
    private fun sendOnePrompt(frame: CommonContainerFixture, pid: Long, sessionId: String, prompt: String = PROMPT) {
        val answersBefore = answers(sessionId)
        focusTheThreadsTab(frame)
        RemoteIde.robot.keyboard {
            enterText(prompt)
            enter()
        }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude's answer to \"$prompt\"") { answers(sessionId) > answersBefore }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle after its answer") { RemoteIde.sessionField(pid, "status") == "idle" }
    }

    private fun answers(sessionId: String): Int {
        val assistant = Regex("\"type\"\\s*:\\s*\"assistant\"")
        return RemoteIde.transcript(sessionId).count { assistant.containsMatchIn(it) }
    }

    /** A click on the thread's editor tab, the one that is not README.md, which puts the focus into its terminal. */
    private fun focusTheThreadsTab(frame: CommonContainerFixture) {
        RemoteIde.toFront(project)
        val tabs = frame.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='EditorTabLabel']"))
        val thread = tabs.first { tab -> tab.findAllText().none { it.text.contains("README") } }
        thread.findAllText().firstOrNull()?.click() ?: thread.click(Point(8, 8))
        Thread.sleep(1_000)
    }

    private fun newClaude(before: Set<Long>): Long {
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        return started.single()
    }

    /** A first thread whose trust question gets the Enter a person would give it, closed again, ThreadUiTest's way. */
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
    }
}
