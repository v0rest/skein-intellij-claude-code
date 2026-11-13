package io.github.v0rest.skein.testutil

import io.github.v0rest.skein.terminal.ThreadTab
import io.github.v0rest.skein.terminal.ThreadTerminal
import kotlinx.coroutines.delay
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * A [ThreadTerminal] whose tabs run real shells, for end-to-end tests. [fake] keeps the books as in every other test,
 * and each tab it opens gets a shell from [rig] in the tab's folder, a child of the test JVM, which stands in for the
 * IDE. Typing writes the command and a newline into that shell's stdin, as a terminal passes on what is typed, so a
 * `claude` started there has the chain claude -> shell -> JVM that Skein ties a thread by. A tab's session runs while
 * its shell does.
 */
class ShellThreadTerminal(private val rig: FakeClaudeRig, val fake: FakeThreadTerminal = FakeThreadTerminal()) : ThreadTerminal by fake {
    private val shells = ConcurrentHashMap<String, Process>()

    init {
        fake.onOpen = { tab -> shells[tab.id] = rig.shell(Path.of(tab.folder)) }
    }

    /** The shell of the thread's tab. */
    fun shellOf(threadId: String): Process = shells.getValue(threadId)

    override fun type(tab: ThreadTab, command: String) {
        fake.type(tab, command)
        shellOf(tab.id).outputStream.apply {
            write("$command\n".toByteArray())
            flush()
        }
    }

    /** Ends what is typed into the tab, as a terminal's EOF does, and the shell exits once its command has. */
    fun endInput(threadId: String) {
        shellOf(threadId).outputStream.close()
    }

    override fun sessionRunning(tab: ThreadTab): Boolean = shells[tab.id]?.isAlive == true

    override fun sessionStarted(tab: ThreadTab): Boolean = shells.containsKey(tab.id)

    override suspend fun awaitEnded(tab: ThreadTab) {
        val shell = shells[tab.id] ?: return
        while (shell.isAlive) delay(20)
    }
}
