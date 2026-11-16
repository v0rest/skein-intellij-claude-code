package io.github.v0rest.skein.ui

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.awt.Point
import java.time.Duration

/**
 * A thread's notification when the owner looks at another project of the same IDE: its balloon shows in the project in
 * front, naming the thread's own project, instead of in a window or window tab nobody sees.
 */
class NotifyUiTest {
    private companion object {
        /** An answer that takes a few seconds, so the other project is surely in front when the turn ends. */
        const val PROMPT = "write a 150-word paragraph about rivers"
    }

    private val demo = DemoProject()
    private var other: String? = null
    private var otherRepo: String? = null

    /** Closes the second project after the screenshot of a failure, which needs it still open to show its balloon. */
    private val otherProject = object : ExternalResource() {
        override fun after() {
            other?.let(RemoteIde::close)
            otherRepo?.let(RemoteIde::cleanUp)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(demo).around(otherProject).around(ScreenshotOnFailure { other ?: demo.name })

    private val project get() = demo.name!!

    @Test
    fun aTurnThatEndsBehindAnotherProjectShowsItsBalloonInTheProjectInFront() {
        val before = RemoteIde.claudePids()
        RemoteIde.startClaudeFromTheEmptyList(project)
        var started = emptySet<Long>()
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a new claude") {
            started = RemoteIde.claudePids() - before
            started.size == 1
        }
        val pid = started.single()
        RemoteIde.acceptTheTrustQuestion(project)
        waitFor(Duration.ofSeconds(90), Duration.ofSeconds(1), "the status file of claude $pid") { pid in RemoteIde.sessionPids() }
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "claude $pid idle") { RemoteIde.sessionField(pid, "status") == "idle" }

        // the other project is open first, so switching to it after the Enter takes a moment, not a project load
        otherRepo = RemoteIde.newRepository()
        other = RemoteIde.open(otherRepo!!)
        focusTheThreadsTab(RemoteIde.frame(project))
        RemoteIde.robot.keyboard {
            enterText(PROMPT)
            enter()
        }
        RemoteIde.focusProject(other!!)
        // a switch that did not happen would look like the balloon going astray
        waitFor(Duration.ofSeconds(10), Duration.ofMillis(250), "${other!!} in front") { RemoteIde.inFront() == other }

        // the turn ends with the other project in front: its balloon names this project and says what happened
        val expected = "$project · finished its turn"
        waitFor(Duration.ofSeconds(150), Duration.ofSeconds(2), "a balloon in ${other!!} about the thread of $project") {
            RemoteIde.notificationsOf(other!!).any { it.endsWith("| $expected") }
        }
        assertTrue(
            "the balloon also went to $project, which is behind",
            RemoteIde.notificationsOf(project).none { it.contains("finished its turn") },
        )
    }

    private fun focusTheThreadsTab(frame: CommonContainerFixture) {
        RemoteIde.focusProject(project)
        Thread.sleep(1_000)
        val tabs = frame.findAll(CommonContainerFixture::class.java, byXpath("//div[@class='EditorTabLabel']"))
        val thread = tabs.first { tab -> tab.findAllText().none { it.text.contains("README") } }
        thread.findAllText().firstOrNull()?.click() ?: thread.click(Point(8, 8))
        Thread.sleep(1_000)
    }
}
