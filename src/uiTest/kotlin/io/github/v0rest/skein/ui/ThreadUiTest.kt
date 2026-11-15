package io.github.v0rest.skein.ui

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

/** A thread's life in a real IDE with the user's real `claude` - started from "+", seen, and closed by its tab's ×. */
class ThreadUiTest {
    private companion object {
        const val EMPTY_LIST = "//div[@class='JBList' and contains(@visible_text, 'No threads yet')]"
        const val WORKTREE_BUTTON = "//div[@accessiblename='New Thread in Worktree…']"
    }

    private val demo = DemoProject()

    // the project closes outside the screenshot, so a failing test's picture still shows its frame
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(demo).around(ScreenshotOnFailure { demo.name })

    private val repo get() = demo.repo
    private val project get() = demo.name!!

    @Test
    fun theToolWindowIsOnAndOffersANewThread() {
        // an empty list with its link; Skein turned off would show its reason instead
        RemoteIde.frame(project).find(ComponentFixture::class.java, byXpath(EMPTY_LIST), Duration.ofSeconds(30))
    }

    @Test
    fun aNewThreadStartsClaudeAndItsTabsCloseEndsIt() {
        val before = RemoteIde.claudePids()
        val frame = RemoteIde.frame(project)
        startClaudeFromTheEmptyList(frame)

        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        // a fresh folder: claude asks whether to trust it first, and writes no status file while it asks
        acceptTheTrustDialog()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }

        closeTheThreadsTab(frame)
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { pid !in RemoteIde.claudePids() }
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }
        assertEquals("", RemoteIde.gitStatus(repo).trim())
    }

    @Test
    fun aWorktreeThreadStartsInItsWorktreeAndGitStaysClean() {
        val frame = RemoteIde.frame(project)
        // claude --worktree refuses a folder whose trust dialog nobody accepted yet, so a plain thread comes first and
        // its trust dialog gets the Enter a person would give it
        trustTheFolder(frame)
        val before = RemoteIde.claudePids()
        RemoteIde.toFront(project)
        // a click into the list gives the tool window the focus, which shows its title bar buttons; a project still
        // loading can take it back, so the click repeats until the branch button next to "+" shows
        val list = frame.find(ComponentFixture::class.java, byXpath(EMPTY_LIST), Duration.ofSeconds(30))
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(3), "New Thread in Worktree… in the Skein title bar") {
            list.click(Point(20, 20))
            frame.findAll(ComponentFixture::class.java, byXpath(WORKTREE_BUTTON)).isNotEmpty()
        }
        frame.find(ComponentFixture::class.java, byXpath(WORKTREE_BUTTON)).click()

        val dialog = RemoteIde.robot.find(
            com.intellij.remoterobot.fixtures.CommonContainerFixture::class.java,
            byXpath("//div[@class='MyDialog']"),
            Duration.ofSeconds(15),
        )
        val name = "ui-task"
        val field = dialog.find(ComponentFixture::class.java, byXpath("//div[@class='JBTextField' or @class='JTextField' or @class='ExtendableTextField']"))
        field.click()
        RemoteIde.robot.keyboard { hotKey(java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_A); enterText(name) }
        dialog.find(ComponentFixture::class.java, byXpath("//div[@class='JButton' and @text='OK']")).click()

        val worktree = RemoteIde.child(repo, ".claude", "worktrees", name)
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the worktree folder") { RemoteIde.exists(worktree) }
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        // claude works in the worktree, and the guard keeps the worktree out of the repository's git
        val cwd = RemoteIde.sessionCwd(pid)
        assertTrue("claude's cwd $cwd is not $worktree", RemoteIde.sameFolder(cwd, worktree))
        assertEquals("", RemoteIde.gitStatus(repo).trim())
    }

    /**
     * Claude Code's "Accessing workspace" question in the thread's tab, which has the focus. Its first answer is "No,
     * exit", so Down and Enter pick "Yes, I trust this folder" - but only once the question is on screen.
     */
    private fun acceptTheTrustDialog() {
        val frame = RemoteIde.frame(project)
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude's question about trusting the folder") {
            frame.findAllText().any { it.text.contains("trust this folder") }
        }
        RemoteIde.robot.keyboard {
            key(java.awt.event.KeyEvent.VK_DOWN)
            enter()
        }
    }

    private fun trustTheFolder(frame: com.intellij.remoterobot.fixtures.CommonContainerFixture) {
        val before = RemoteIde.claudePids()
        startClaudeFromTheEmptyList(frame)
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        acceptTheTrustDialog()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        closeTheThreadsTab(frame)
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { pid !in RemoteIde.claudePids() }
    }

    /**
     * The "Start claude in a new tab" link of an empty list, which runs New Thread like "+" does. "+" itself sits in the
     * title bar, which the new UI shows only while the IDE's window has the system's focus - something a robot-driven
     * window on another machine does not reliably get - so the link is the dependable way in.
     */
    private fun startClaudeFromTheEmptyList(frame: com.intellij.remoterobot.fixtures.CommonContainerFixture) {
        RemoteIde.toFront(project)
        val list = frame.find(ComponentFixture::class.java, byXpath(EMPTY_LIST), Duration.ofSeconds(30))
        // the robot reads the text the list paints, with where it painted it
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the link in the empty list") {
            list.findAllText().any { it.text.contains("Start claude") }
        }
        list.findAllText().first { it.text.contains("Start claude") }.click()
    }

    private fun closeTheThreadsTab(frame: com.intellij.remoterobot.fixtures.CommonContainerFixture) {
        // the thread's editor tab is the one whose close button sits next to a terminal tab; README.md may be open too
        val tabs = frame.findAll(com.intellij.remoterobot.fixtures.CommonContainerFixture::class.java, byXpath("//div[@class='EditorTabLabel']"))
        val thread = tabs.first { tab -> tab.findAllText().none { it.text.contains("README") } }
        thread.find(ComponentFixture::class.java, byXpath(".//div[@class='InplaceButton']")).click()
    }
}
