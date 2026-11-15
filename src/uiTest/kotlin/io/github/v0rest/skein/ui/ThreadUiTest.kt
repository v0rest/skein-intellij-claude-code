package io.github.v0rest.skein.ui

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.awt.Point
import java.time.Duration

/**
 * A thread's life in a real IDE with the user's real `claude` - started from "+", seen, and closed by its tab's ×, and
 * what Skein shows while claude asks whether to trust a fresh folder.
 */
class ThreadUiTest {
    private companion object {
        const val EMPTY_LIST = "//div[@class='JBList' and contains(@visible_text, 'No threads yet')]"
        const val WORKTREE_BUTTON = "//div[@accessiblename='New Thread in Worktree…']"

        /** A piece of the row's words for a claude asking about trust, short enough to survive the row cutting its line 2. */
        const val ASKS_TRUST = "asks whether"

        /** A word of the name dialog's trust warning, which wraps over several lines. */
        const val REFUSES = "refuses"
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
        val dialog = openTheWorktreeDialog(frame)
        // the folder is trusted now, so the dialog has no word about it - asked once the dialog shows its name row
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the dialog's name row") { dialog.findAllText().any { it.text.contains("Name:") } }
        assertFalse("a trust warning for a trusted folder", dialog.findAllText().any { it.text.contains(REFUSES) })
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
     * A thread in a fresh folder - claude asks whether to trust it and writes no status file while it asks, so once
     * Skein's launch timeout (30 s) has passed the row says what it waits for, instead of "status unknown". The answer
     * lets claude start, and the row goes on from there.
     */
    @Test
    fun aThreadInAFreshFolderShowsTheTrustQuestionOnItsRowUntilItIsAnswered() {
        val before = RemoteIde.claudePids()
        val list = startClaudeFromTheEmptyList(RemoteIde.frame(project))
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        // the list is only read, never clicked, so the thread's tab keeps the focus for the answer below
        waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "the row saying claude asks about trust") { rowSays(list, ASKS_TRUST) }
        assertTrue("claude $pid wrote its status file before the answer", pid !in RemoteIde.sessionPids())

        acceptTheTrustDialog()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the row past the trust question") { !rowSays(list, ASKS_TRUST) }
    }

    /** New Thread in Worktree… in a fresh folder: `claude --worktree` would refuse it, so the dialog says so. Then Cancel. */
    @Test
    fun newThreadInWorktreeWarnsAboutAFolderNobodyTrustedYet() {
        val before = RemoteIde.claudePids()
        val dialog = openTheWorktreeDialog(RemoteIde.frame(project))
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the trust warning in the dialog") {
            dialog.findAllText().any { it.text.contains(REFUSES) }
        }
        // a warning only, OK stays enabled, since the reading may be wrong for a newer Claude Code
        val ok = dialog.find(ComponentFixture::class.java, byXpath("//div[@class='JButton' and @text='OK']"))
        assertEquals("true", ok.callJs<String>("'' + component.isEnabled()").trim())
        dialog.find(ComponentFixture::class.java, byXpath("//div[@class='JButton' and @text='Cancel']")).click()
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the dialog closed") {
            RemoteIde.robot.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='MyDialog']")).isEmpty()
        }
        assertEquals("Cancel started a claude", before, RemoteIde.claudePids())
        assertEquals("", RemoteIde.gitStatus(repo).trim())
    }

    private fun rowSays(list: ComponentFixture, words: String): Boolean = list.findAllText().any { it.text.contains(words) }

    /**
     * New Thread in Worktree… from its own button next to "+", for a project whose list is empty, and the name dialog it
     * opens. The title bar's buttons show only while the tool window has the focus, which a click into the list gives it.
     */
    private fun openTheWorktreeDialog(frame: CommonContainerFixture): CommonContainerFixture {
        RemoteIde.toFront(project)
        val list = frame.find(ComponentFixture::class.java, byXpath(EMPTY_LIST), Duration.ofSeconds(30))
        // a project still loading (README.md opening in its preview) can take the focus back, so the tool window is
        // activated and clicked again until its title bar shows the branch button next to "+"
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(3), "New Thread in Worktree… in the Skein title bar") {
            RemoteIde.activateSkein(project)
            list.click(Point(20, 20))
            frame.findAll(ComponentFixture::class.java, byXpath(WORKTREE_BUTTON)).isNotEmpty()
        }
        frame.find(ComponentFixture::class.java, byXpath(WORKTREE_BUTTON)).click()
        return RemoteIde.robot.find(CommonContainerFixture::class.java, byXpath("//div[@class='MyDialog']"), Duration.ofSeconds(15))
    }

    /**
     * Claude Code's "Accessing workspace" question in the thread's tab, which has the focus. Its first answer is "No,
     * exit", so Down and Enter pick "Yes, I trust this folder" - but only once the question is on screen. Skein's row and
     * balloon say "asks whether to trust this folder" too, and those are not the question.
     */
    private fun acceptTheTrustDialog() {
        val frame = RemoteIde.frame(project)
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude's question about trusting the folder") {
            frame.findAllText().any { it.text.contains("trust this folder") && !it.text.contains(ASKS_TRUST) }
        }
        RemoteIde.robot.keyboard {
            key(java.awt.event.KeyEvent.VK_DOWN)
            enter()
        }
    }

    private fun trustTheFolder(frame: CommonContainerFixture) {
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
     * window on another machine does not reliably get - so the link is the dependable way in. Returns the list, which
     * then shows the thread's row.
     */
    private fun startClaudeFromTheEmptyList(frame: CommonContainerFixture): ComponentFixture {
        RemoteIde.toFront(project)
        val list = frame.find(ComponentFixture::class.java, byXpath(EMPTY_LIST), Duration.ofSeconds(30))
        // the robot reads the text the list paints, with where it painted it
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the link in the empty list") {
            list.findAllText().any { it.text.contains("Start claude") }
        }
        list.findAllText().first { it.text.contains("Start claude") }.click()
        return list
    }

    private fun closeTheThreadsTab(frame: CommonContainerFixture) {
        // the thread's editor tab is the one whose close button sits next to a terminal tab; README.md may be open too
        val tabs = frame.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='EditorTabLabel']"))
        val thread = tabs.first { tab -> tab.findAllText().none { it.text.contains("README") } }
        thread.find(ComponentFixture::class.java, byXpath(".//div[@class='InplaceButton']")).click()
    }
}
