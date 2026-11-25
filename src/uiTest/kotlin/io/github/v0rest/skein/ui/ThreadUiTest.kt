package io.github.v0rest.skein.ui

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
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
 * A thread's life in a real IDE with the user's real `claude` - started from "+", seen, and closed by its tab's × or past
 * the close check the way a middle click closes it, and what Skein shows while claude asks whether to trust a fresh folder.
 */
class ThreadUiTest {
    private companion object {
        const val EMPTY_LIST = "//div[@class='JBList' and contains(@visible_text, 'No threads yet')]"
        const val WORKTREE_BUTTON = "//div[@accessiblename='New Thread in Worktree…']"

        /** A piece of the row's words for a claude asking about trust, short enough to survive the row cutting its line 2. */
        const val ASKS_TRUST = "asks whether"

        /** A word of the name dialog's trust warning, which wraps over several lines. */
        const val REFUSES = "refuses"

        /** A prompt that keeps claude at its answer well past the close. */
        const val LONG_PROMPT = "Write a detailed essay of about 1500 words on the history of tea, in plain text, no headings."

        /** A prompt whose answer is a few tokens, sent only so the session has a transcript the History lists. */
        const val SHORT_PROMPT = "Reply with the single word ok."

        /** An answer of claude's in a transcript line. */
        val ASSISTANT = Regex("\"type\"\\s*:\\s*\"assistant\"")
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
        RemoteIde.startClaudeFromTheEmptyList(project)

        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        // a fresh folder: claude asks whether to trust it first, and writes no status file while it asks
        RemoteIde.acceptTheTrustQuestion(project)
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }

        closeTheThreadsTab(frame)
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { pid !in RemoteIde.claudePids() }
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }
        assertEquals("", RemoteIde.gitStatus(repo).trim())
    }

    /**
     * An end-to-end guard for a tab closed the moment claude's status file appears - claude still has to end. The status
     * file is polled every 100 ms and the tab's close button is found beforehand, so the click usually lands before
     * Skein's next look (once a second) ties the thread to the tab's shell, but not always, and then it is an ordinary
     * close.
     */
    @Test
    fun aTabClosedAsSoonAsClaudeIsUpStillEndsIt() {
        val before = RemoteIde.claudePids()
        val frame = RemoteIde.frame(project)
        RemoteIde.startClaudeFromTheEmptyList(project)

        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        val start = RemoteIde.startOf(pid)
        try {
            // found before the answer, so nothing but the poll stands between the status file and the click
            val close = threadsTabCloseButton(frame)
            // a fresh folder, so claude writes its status file only once the trust question is answered
            RemoteIde.acceptTheTrustQuestion(project)
            waitFor(Duration.ofSeconds(60), Duration.ofMillis(100), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
            close.click()

            // by pid, since a claude whose shell is gone no longer counts among the IDE's claudes
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { !RemoteIde.alive(pid) }
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }
            assertEquals("", RemoteIde.gitStatus(repo).trim())
        } finally {
            // a claude left hanging must not outlive the test, and the clean-up after it finds one only by its shell
            RemoteIde.destroy(pid, start)
        }
    }

    /**
     * A busy thread's tab closed the way a middle click or a Shift+click on it closes it - `closeFile(file, window)`, which
     * asks no close check (`TabMouseListener.mouseReleased`). The terminal goes with the tab, so Skein ends claude first,
     * while the tab is still read, and the thread goes as after Close - claude gone by pid, its status file gone, the row
     * gone, and no claude of the thread left behind or started again. The prompt goes in through the terminal view's own
     * send API, its Enter on its own, and the tab closes while claude is at its answer (a few tokens).
     */
    @Test
    fun aBusyThreadsTabClosedPastTheCloseCheckEndsClaudeAndTheThreadGoes() {
        val before = RemoteIde.claudePids()
        RemoteIde.startClaudeFromTheEmptyList(project)
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        val start = RemoteIde.startOf(pid)
        try {
            // a fresh folder, so claude writes its status file only once the trust question is answered
            RemoteIde.acceptTheTrustQuestion(project)
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
            waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle") { RemoteIde.sessionField(pid, "status") == "idle" }
            RemoteIde.sendToTheThreadsTerminal(project, LONG_PROMPT)
            waitFor(Duration.ofSeconds(60), Duration.ofMillis(200), "claude $pid busy") { RemoteIde.sessionField(pid, "status") == "busy" }

            RemoteIde.closeTheThreadsTabWithoutChecks(project)

            // by pid, since a claude whose shell is gone no longer counts among the IDE's claudes
            waitFor(Duration.ofSeconds(20), Duration.ofMillis(500), "claude $pid gone") { !RemoteIde.alive(pid) }
            waitFor(Duration.ofSeconds(20), Duration.ofMillis(500), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }
            waitFor(Duration.ofSeconds(20), Duration.ofMillis(500), "the thread's row gone") { RemoteIde.threadRows(project) == 0 }
            // and it does not come back
            Thread.sleep(3_000)
            assertFalse("the thread's tab came back", RemoteIde.terminalInTheEditor(project))
            assertEquals(0, RemoteIde.threadRows(project))
            assertEquals("a claude of the thread is still there or started again", emptySet<Long>(), RemoteIde.claudePids() - before)
            assertEquals("", RemoteIde.gitStatus(repo).trim())
        } finally {
            // a claude left hanging must not outlive the test, and the clean-up after it finds one only by its shell
            RemoteIde.destroy(pid, start)
        }
    }

    /**
     * An end-to-end guard that closing the project ends its threads' claude too. File | Close Project ends in
     * `closeAndDispose`, and the terminals go with the project, so a claude still running would be left behind with no
     * one reading its terminal. An idle thread, the project closed on the EDT, then claude and its status file gone. The
     * project opened again shows the thread once more, as the threads are saved after Skein ended them.
     */
    @Test
    fun closingTheProjectEndsItsThreadsClaude() {
        val before = RemoteIde.claudePids()
        RemoteIde.startClaudeFromTheEmptyList(project)
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        val start = RemoteIde.startOf(pid)
        try {
            // a fresh folder, so claude writes its status file only once the trust question is answered
            RemoteIde.acceptTheTrustQuestion(project)
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid idle") { RemoteIde.sessionField(pid, "status") == "idle" }

            RemoteIde.closeAndDispose(project)
            // by pid, since a claude whose shell is gone no longer counts among the IDE's claudes
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { !RemoteIde.alive(pid) }
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }

            RemoteIde.open(repo)
            RemoteIde.activateSkein(project)
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the thread back in the reopened project") { RemoteIde.threadRows(project) == 1 }
        } finally {
            // a claude left hanging must not outlive the test, and the clean-up after it finds one only by its shell
            RemoteIde.destroy(pid, start)
        }
    }

    /**
     * A thread's colour stays with its session. A thread gets a colour from its row's menu, its tab is closed with its ×,
     * and its session, resumed from the History with a double click, opens a new thread with that colour on its row.
     * One short prompt goes first (a few tokens), since a session without a turn has no transcript the History could
     * list. Both claudes are ended at the end, by their tabs or, failing that, by pid.
     */
    @Test
    fun aColouredThreadsSessionResumedFromHistoryAfterItsTabClosedKeepsTheColour() {
        val frame = RemoteIde.frame(project)
        val before = RemoteIde.claudePids()
        val list = RemoteIde.startClaudeFromTheEmptyList(project)
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        val start = RemoteIde.startOf(pid)
        // every claude the resume starts, by pid with its start, noted as soon as it shows, so a second one is ended too
        val resumedClaudes = LinkedHashMap<Long, String>()
        try {
            // a fresh folder, so claude writes its status file only once the trust question is answered
            RemoteIde.acceptTheTrustQuestion(project)
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
            waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle") { RemoteIde.sessionField(pid, "status") == "idle" }
            val sessionId = RemoteIde.sessionField(pid, "sessionId")
            assertTrue("claude $pid has no session id", sessionId.isNotEmpty())
            RemoteIde.sendToTheThreadsTerminal(project, SHORT_PROMPT)
            waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude's answer in the transcript of $sessionId") {
                RemoteIde.transcript(sessionId).any { ASSISTANT.containsMatchIn(it) }
            }
            waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle after its answer") { RemoteIde.sessionField(pid, "status") == "idle" }

            colourTheFirstRow(list, "Red")
            waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the row in red") { threadColours(list) == listOf("RED") }

            closeTheThreadsTab(frame)
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { pid !in RemoteIde.claudePids() }
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "the status file of claude $pid gone") { pid !in RemoteIde.sessionPids() }
            waitFor(Duration.ofSeconds(20), Duration.ofSeconds(1), "the thread's row gone") { RemoteIde.threadRows(project) == 0 }

            val history = openTheHistory(frame)
            var row = -1
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "session $sessionId in the History") {
                row = historyRowOf(history, sessionId)
                row >= 0
            }
            val beforeResume = RemoteIde.claudePids()
            history.doubleClick(middleOfRow(history, row))
            waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the claude of the resumed session") {
                for (newPid in RemoteIde.claudePids() - beforeResume) {
                    if (newPid !in resumedClaudes) resumedClaudes[newPid] = RemoteIde.startOf(newPid)
                }
                resumedClaudes.size == 1
            }
            val resumedPid = resumedClaudes.keys.single()
            waitFor(Duration.ofSeconds(20), Duration.ofSeconds(1), "the new thread's row") { RemoteIde.threadRows(project) == 1 }
            waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the new thread's row in red") { threadColours(list) == listOf("RED") }

            closeTheThreadsTab(frame)
            waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $resumedPid gone") { !RemoteIde.alive(resumedPid) }
        } finally {
            // a claude left hanging must not outlive the test, and the clean-up after it finds one only by its shell
            RemoteIde.destroy(pid, start)
            // and one that showed only after the wait gave up
            for (late in RemoteIde.claudePids() - before - pid) resumedClaudes.getOrPut(late) { RemoteIde.startOf(late) }
            for ((resumedPid, resumedStart) in resumedClaudes) RemoteIde.destroy(resumedPid, resumedStart)
        }
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
        RemoteIde.replaceTextWith(name)
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
        val list = RemoteIde.startClaudeFromTheEmptyList(project)
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        // the list is only read, never clicked, so the thread's tab keeps the focus for the answer below
        waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "the row saying claude asks about trust") { rowSays(list, ASKS_TRUST) }
        assertTrue("claude $pid wrote its status file before the answer", pid !in RemoteIde.sessionPids())

        RemoteIde.acceptTheTrustQuestion(project)
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

    /** The colour of each thread row, top down, by its name ("RED"), or "null" for a row without one. */
    private fun threadColours(list: ComponentFixture): List<String> = list.callJs<String>(
        """
        var m = component.getModel(); var out = [];
        for (var i = 0; i < m.getSize(); i++) out.push('' + m.getElementAt(i).getColor());
        out.join(',')
        """,
    ).trim().split(',').filter { it.isNotEmpty() }

    /** Gives the first thread row the colour [name] from its right-click menu, Color and then [name]. */
    private fun colourTheFirstRow(list: ComponentFixture, name: String) {
        RemoteIde.toFront(project)
        // a right click selects the row under it, and the first row sits at the top of the list
        list.rightClick(Point(60, 12))
        clickShowing("//div[@class='ActionMenu' and @text='Color']")
        clickShowing("//div[@class='ActionMenuItem' and @text='$name']")
    }

    /** Clicks the menu entry [xpath] that is on screen: the IDE can hold a second, hidden copy of a submenu's items. */
    private fun clickShowing(xpath: String) {
        var showing: ComponentFixture? = null
        waitFor(Duration.ofSeconds(10), Duration.ofMillis(300), "a showing $xpath") {
            showing = RemoteIde.robot.findAll(ComponentFixture::class.java, byXpath(xpath)).firstOrNull { it.callJs<Boolean>("component.isShowing()") }
            showing != null
        }
        showing!!.click()
    }

    /**
     * The History's list, opened with a click on its header unless it shows already. It is the list whose empty text
     * speaks of past sessions, the thread list's speaks of threads. Closed, it sits in a hidden scroll pane, which the
     * robot may not find at all, so it is looked for again once the header was clicked.
     */
    private fun openTheHistory(frame: CommonContainerFixture): ComponentFixture {
        RemoteIde.toFront(project)
        // the IDE's own balloons (an update it cannot check, a virus scanner note) sit over the History header on a small
        // screen, and a click there opens their link instead
        expireTheIdesBalloons()
        if (shownHistory(frame) == null) {
            frame.find(ComponentFixture::class.java, byXpath("//div[@class='JBLabel' and starts-with(@text, 'History')]")).click()
        }
        var history: ComponentFixture? = null
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the History open") {
            history = shownHistory(frame)
            history != null
        }
        return history!!
    }

    /** Expires every notification of the test's project, so no balloon covers what the test clicks next. */
    private fun expireTheIdesBalloons() {
        RemoteIde.js(
            """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$project') {
                var ns = com.intellij.notification.NotificationsManager.getNotificationsManager().getNotificationsOfType(com.intellij.notification.Notification, ps[i]);
                for (var j = 0; j < ns.length; j++) ns[j].expire();
            }
            'ok'
            """,
            edt = true,
        )
    }

    /** The History's list while it shows, or null. */
    private fun shownHistory(frame: CommonContainerFixture): ComponentFixture? =
        frame.findAll(ComponentFixture::class.java, byXpath("//div[@class='JBList']")).firstOrNull { list ->
            list.callJs<String>("('' + component.getEmptyText().getText()).indexOf('past sessions') >= 0 && component.isShowing() ? 'yes' : 'no'").trim() == "yes"
        }

    /** The index of session [sessionId]'s row in the History, or -1 while it has none. */
    private fun historyRowOf(history: ComponentFixture, sessionId: String): Int = history.callJs<String>(
        """
        var m = component.getModel(); var out = -1;
        for (var i = 0; i < m.getSize() && out < 0; i++) if (('' + m.getElementAt(i).getEntry().getSessionId()) == '$sessionId') out = i;
        '' + out
        """,
    ).trim().toIntOrNull() ?: -1

    /** The middle of row [index] of [list], where a click lands on that row. */
    private fun middleOfRow(list: ComponentFixture, index: Int): Point {
        val (x, y, width, height) = list.callJs<String>("var r = component.getCellBounds($index, $index); r.x + ',' + r.y + ',' + r.width + ',' + r.height")
            .trim().split(',').map { it.trim().toDouble().toInt() }
        return Point(x + width / 2, y + height / 2)
    }

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
        // the dialog comes once Skein has read the repository's trust and git, which takes a while on a slow machine
        return RemoteIde.robot.find(CommonContainerFixture::class.java, byXpath("//div[@class='MyDialog']"), Duration.ofSeconds(60))
    }


    private fun trustTheFolder(frame: CommonContainerFixture) {
        val before = RemoteIde.claudePids()
        RemoteIde.startClaudeFromTheEmptyList(project)
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude.exe") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        RemoteIde.acceptTheTrustQuestion(project)
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        closeTheThreadsTab(frame)
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid gone") { pid !in RemoteIde.claudePids() }
    }

    private fun closeTheThreadsTab(frame: CommonContainerFixture) = threadsTabCloseButton(frame).click()

    /** The close button of the thread's editor tab, the one tab that is not README.md, which may be open too. */
    private fun threadsTabCloseButton(frame: CommonContainerFixture): ComponentFixture {
        val tabs = frame.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='EditorTabLabel']"))
        val thread = tabs.first { tab -> tab.findAllText().none { it.text.contains("README") } }
        return thread.find(ComponentFixture::class.java, byXpath(".//div[@class='InplaceButton']"))
    }
}
