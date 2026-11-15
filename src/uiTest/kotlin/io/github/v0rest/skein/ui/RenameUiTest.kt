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
 * Rename with "Also rename in Claude Code" in a real IDE with the user's real `claude`. A claude Skein just started counts
 * as dirty until the first turn the person starts, so each test first sends one short prompt with the robot keyboard
 * (a few tokens). Then: on the idle thread Skein types `/rename` into claude and the transcript gets the name as its
 * custom title. With a draft typed, or text pasted, into the thread's tab - after a first rename that went, so the tab
 * was clean before, and with the words seen in claude's input - a second rename is not typed while that is there. The
 * draft and the paste are never sent.
 */
class RenameUiTest {
    private companion object {
        const val RENAME_ITEM = "//div[@class='ActionMenuItem' and contains(@text, 'Rename')]"
        const val CLAUDE_BOX = "//div[@class='JBCheckBox' and @text='Also rename in Claude Code']"

        /**
         * The one prompt each test sends. Its answer takes a few seconds on purpose: Skein reads claude's status about once
         * a second, and a turn over within that second is never seen, so the tab would not count as clean after a "say ok".
         */
        const val PROMPT = "write a 150-word paragraph about rivers"
    }

    private val demo = DemoProject()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(demo).around(ScreenshotOnFailure { demo.name })

    private val project get() = demo.name!!

    @Test
    fun renameWithTheBoxReachesTheTranscriptOfAnIdleThread() {
        val frame = RemoteIde.frame(project)
        trustTheFolder(frame)
        val (list, pid, sessionId) = startAnIdleThread(frame)
        sendOnePrompt(frame, pid, sessionId)

        renameFromTheRow(list, "demo-shop-ui")

        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the custom title in the transcript of $sessionId") {
            customTitles(sessionId).contains("demo-shop-ui")
        }
        // a local command: claude is idle again, and the line never went to the model as a prompt
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude $pid idle again") { RemoteIde.sessionField(pid, "status") == "idle" }
        val user = Regex("\"type\"\\s*:\\s*\"user\"")
        assertTrue("the line went to the model", RemoteIde.transcript(sessionId).none { user.containsMatchIn(it) && it.contains("/rename demo-shop-ui\"") })
    }

    @Test
    fun aDraftInTheThreadsTabKeepsTheRenameWaiting() {
        val frame = RemoteIde.frame(project)
        trustTheFolder(frame)
        val (list, pid, sessionId) = startAnIdleThread(frame)
        sendOnePrompt(frame, pid, sessionId)
        showTheTabIsClean(list, sessionId, "demo-shop-first")
        // into claude's input, never sent
        focusTheThreadsTab(frame)
        RemoteIde.robot.keyboard { enterText("draft words") }

        renameAndSeeNothingReachTheTranscript(frame, list, pid, sessionId, "demo-shop-second", "draft words")
    }

    /** A paste is an IDE action, not a key the terminal's key listener hears, and it leaves text in the input all the same. */
    @Test
    fun aPasteInTheThreadsTabKeepsTheRenameWaiting() {
        val frame = RemoteIde.frame(project)
        trustTheFolder(frame)
        val (list, pid, sessionId) = startAnIdleThread(frame)
        sendOnePrompt(frame, pid, sessionId)
        showTheTabIsClean(list, sessionId, "demo-shop-first")
        RemoteIde.js(
            """
            com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection('pasted words'));
            'ok'
            """,
            edt = true,
        )
        focusTheThreadsTab(frame)
        val paste = pasteKeys()
        RemoteIde.robot.keyboard { hotKey(*paste) }

        renameAndSeeNothingReachTheTranscript(frame, list, pid, sessionId, "demo-shop-second", "pasted words")
    }

    /**
     * A first rename with the box that does reach the transcript: the tab's input counts as clean now, so what the test
     * does next is the only reason a second rename would wait.
     */
    private fun showTheTabIsClean(list: ComponentFixture, sessionId: String, name: String) {
        renameFromTheRow(list, name)
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the first custom title in the transcript of $sessionId") {
            customTitles(sessionId).contains(name)
        }
    }

    /**
     * The keys of the terminal's paste in the IDE's keymap on that machine (`Terminal.Paste`, else `$Paste`), its first
     * single-stroke shortcut: the modifiers' keys and then the key.
     */
    private fun pasteKeys(): IntArray {
        val stroke = RemoteIde.js(
            """
            var km = com.intellij.openapi.keymap.KeymapManager.getInstance().getActiveKeymap();
            var ids = ['Terminal.Paste', '${'$'}Paste']; var out = '';
            for (var j = 0; j < ids.length && out == ''; j++) {
                var shortcuts = km.getShortcuts(ids[j]);
                for (var i = 0; i < shortcuts.length; i++) {
                    var sc = shortcuts[i];
                    if (sc instanceof com.intellij.openapi.actionSystem.KeyboardShortcut && sc.getSecondKeyStroke() == null) {
                        var ks = sc.getFirstKeyStroke(); out = ks.getKeyCode() + ',' + ks.getModifiers(); break;
                    }
                }
            }
            out
            """,
        )
        // a KeyStroke keeps its modifiers in the extended masks as well
        val (code, modifiers) = stroke.split(',').map { it.trim().toInt() }
        val keys = buildList {
            if (modifiers and java.awt.event.InputEvent.CTRL_DOWN_MASK != 0) add(java.awt.event.KeyEvent.VK_CONTROL)
            if (modifiers and java.awt.event.InputEvent.META_DOWN_MASK != 0) add(java.awt.event.KeyEvent.VK_META)
            if (modifiers and java.awt.event.InputEvent.ALT_DOWN_MASK != 0) add(java.awt.event.KeyEvent.VK_ALT)
            if (modifiers and java.awt.event.InputEvent.SHIFT_DOWN_MASK != 0) add(java.awt.event.KeyEvent.VK_SHIFT)
            add(code)
        }
        return keys.toIntArray()
    }

    /**
     * Sees [left] in claude's input on screen first, then renames to [name] with the box and checks for 20 s that neither
     * the name nor [left] reach the transcript.
     */
    private fun renameAndSeeNothingReachTheTranscript(frame: CommonContainerFixture, list: ComponentFixture, pid: Long, sessionId: String, name: String, left: String) {
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "\"$left\" in claude's input") { RemoteIde.editorsContain(left) }
        renameFromTheRow(list, name)
        // the Skein name shows at once, whatever Claude Code gets
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the Skein name on the row") { list.findAllText().any { it.text.contains(name) } }
        // well past the guard's pause after the last key and many looks of the thread service
        Thread.sleep(20_000)
        val lines = RemoteIde.transcript(sessionId)
        assertEquals("the rename reached the transcript with \"$left\" in the input", emptyList<String>(), lines.filter { it.contains(name) })
        assertTrue("\"$left\" went to the model", lines.none { it.contains(left) })
        assertEquals("idle", RemoteIde.sessionField(pid, "status"))
    }

    /**
     * [PROMPT] and Enter into the thread's idle claude, then waits for its answer and for claude to be idle again: the
     * first turn the person starts, after which Skein counts the tab's input as clean.
     */
    private fun sendOnePrompt(frame: CommonContainerFixture, pid: Long, sessionId: String) {
        val answersBefore = answers(sessionId)
        focusTheThreadsTab(frame)
        RemoteIde.robot.keyboard {
            enterText(PROMPT)
            enter()
        }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude's answer to \"$PROMPT\"") { answers(sessionId) > answersBefore }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle after its answer") { RemoteIde.sessionField(pid, "status") == "idle" }
    }

    private fun answers(sessionId: String): Int {
        val assistant = Regex("\"type\"\\s*:\\s*\"assistant\"")
        return RemoteIde.transcript(sessionId).count { assistant.containsMatchIn(it) }
    }

    /**
     * Starts a thread from the empty list in the trusted folder and waits until its claude is idle; the list, the new
     * claude's pid and its session. The tab has the focus, as a new thread's does.
     */
    private fun startAnIdleThread(frame: CommonContainerFixture): Triple<ComponentFixture, Long, String> {
        val before = RemoteIde.claudePids()
        val list = RemoteIde.startClaudeFromTheEmptyList(project)
        val pid = newClaude(before)
        waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle") { RemoteIde.sessionField(pid, "status") == "idle" }
        val sessionId = RemoteIde.sessionField(pid, "sessionId")
        assertTrue("claude $pid has no session id", sessionId.isNotEmpty())
        return Triple(list, pid, sessionId)
    }

    /**
     * A click on the thread's editor tab, which puts the focus into its terminal; README.md may be open beside it, and
     * the tab's close button sits at its right end.
     */
    private fun focusTheThreadsTab(frame: CommonContainerFixture) {
        RemoteIde.toFront(project)
        val tabs = frame.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='EditorTabLabel']"))
        val thread = tabs.first { tab -> tab.findAllText().none { it.text.contains("README") } }
        thread.findAllText().firstOrNull()?.click() ?: thread.click(Point(8, 8))
        Thread.sleep(1_000)
    }

    /** Rename… from the thread row's right-click menu: [name] in the field, the box ticked, OK. */
    private fun renameFromTheRow(list: ComponentFixture, name: String) {
        RemoteIde.toFront(project)
        // a right click selects the row under it; the first row sits at the top of the list
        list.rightClick(Point(60, 12))
        RemoteIde.robot.find(ComponentFixture::class.java, byXpath(RENAME_ITEM), Duration.ofSeconds(10)).click()
        val dialog = RemoteIde.robot.find(CommonContainerFixture::class.java, byXpath("//div[@class='MyDialog']"), Duration.ofSeconds(30))
        val field = dialog.find(ComponentFixture::class.java, byXpath("//div[@class='JBTextField']"))
        field.click()
        RemoteIde.replaceTextWith(name)
        val box = dialog.find(ComponentFixture::class.java, byXpath(CLAUDE_BOX))
        if (box.callJs<String>("'' + component.isSelected()").trim() != "true") box.click()
        assertEquals("true", box.callJs<String>("'' + component.isSelected()").trim())
        dialog.find(ComponentFixture::class.java, byXpath("//div[@class='JButton' and @text='OK']")).click()
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the Rename dialog closed") {
            RemoteIde.robot.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='MyDialog']")).isEmpty()
        }
    }

    private fun customTitles(sessionId: String) = RemoteIde.transcript(sessionId).filter { it.contains("\"custom-title\"") }.joinToString("\n")

    private fun newClaude(before: Set<Long>): Long {
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        return started.single()
    }

    /** A first thread whose trust question gets the Enter a person would give it, closed again; ThreadUiTest's way. */
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
