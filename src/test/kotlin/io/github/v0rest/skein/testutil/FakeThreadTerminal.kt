package io.github.v0rest.skein.testutil

import io.github.v0rest.skein.process.ProcessList
import io.github.v0rest.skein.terminal.ClaudeCommand
import io.github.v0rest.skein.terminal.CloseOrder
import io.github.v0rest.skein.terminal.OwnSelections
import io.github.v0rest.skein.terminal.ShellsAtCommand
import io.github.v0rest.skein.terminal.TabsInView
import io.github.v0rest.skein.terminal.ThreadTab
import io.github.v0rest.skein.terminal.ThreadTerminal
import io.github.v0rest.skein.terminal.ThreadTerminalListener

/**
 * Records what the coordinator asks of the terminal, and refuses any command [ClaudeCommand] does not allow. Its close
 * behaves like the IDE's where it matters: the tabs close in [CloseOrder.closeInOrder], the one in front last, and when
 * the tab in front goes, the IDE selects a neighbour and the listener hears of it, unless Skein's own selections
 * ([OwnSelections], as the real terminal keeps them) say it was not the user.
 */
class FakeThreadTerminal : ThreadTerminal {
    /** A tab; [shellsAtCommand] is what [FakeThreadTerminal.shellsNow] gave when a command was typed into it. */
    class Tab(override val id: String, override val folder: String, val name: String = "") : ThreadTab {
        override var shellsAtCommand: ShellsAtCommand = ShellsAtCommand.NoCommandYet
    }

    val opened = mutableListOf<Tab>()
    val typed = mutableListOf<Pair<String, String>>()
    val selected = mutableListOf<String>()
    /** The ids of each batch of tabs asked to close, a list per call. */
    val closed = mutableListOf<List<String>>()

    /**
     * Endings, waits and closes in the order they came ("ending t1", "await t1", "close t1,t3"); a test adds its own
     * entries (a hang-up) to see where they fall.
     */
    val calls = mutableListOf<String>()
    var attached: ThreadTerminalListener? = null
    var inView: Set<String> = emptySet()

    /**
     * Called when a tab's shell starts; tests use it to start that shell in their fake process tree. That is when the tab
     * opens, or with [lazyShells] for a tab opened out of view, when it is first shown ([show]).
     */
    var onOpen: (Tab) -> Unit = {}

    /** Tabs opened out of view start their shell only when first shown, as the real terminal's do; off by default. */
    var lazyShells = false

    /** The tabs whose session has not started yet (opened out of view with [lazyShells], not shown since). */
    val notStarted = mutableSetOf<String>()

    /** The IDE's child processes when a command is typed, as the real terminal lists them; null when that fails. */
    var shellsNow: () -> ProcessList? = { null }

    /** How a tab's session ends once awaited; at once unless a test says otherwise. */
    var ends: suspend (ThreadTab) -> Unit = {}

    /** The tabs whose session does not run (not started yet, or ended); every other tab's runs. */
    val notRunning = mutableSetOf<String>()

    /** The editor tabs still open, in the order the IDE shows them, and the one in front. */
    val tabRow = mutableListOf<String>()
    var front: String? = null

    /** Skein's own selections, with a clock that stands still, so only the guard itself decides. */
    val own = OwnSelections(quietMillis = 1_000) { 0L }

    override fun open(threadId: String, folder: String, tabName: String, focus: Boolean): ThreadTab =
        Tab(threadId, folder, tabName).also {
            opened += it
            tabRow += threadId
            if (lazyShells && !focus) notStarted += threadId else onOpen(it)
        }

    /** The tab comes into view; a tab whose session has not started starts its shell now. */
    fun show(threadId: String) {
        if (notStarted.remove(threadId)) onOpen(opened.single { it.id == threadId })
    }

    override fun type(tab: ThreadTab, command: String) {
        require(ClaudeCommand.isAllowed(command)) { "Skein may only type claude, claude --resume <id> or claude --worktree <name>, got: $command" }
        (tab as Tab).shellsAtCommand = shellsNow()?.let { ShellsAtCommand.Listed(it) } ?: ShellsAtCommand.NotListed
        typed += tab.id to command
    }

    override fun select(tab: ThreadTab) {
        selected += tab.id
    }

    override fun close(tabs: List<ThreadTab>) {
        closed += tabs.map { it.id }
        calls += "close " + tabs.joinToString(",") { it.id }
        CloseOrder.closeInOrder(tabs, front, own) { tab -> closeLikeTheIde(tab.id) }
    }

    /** The tab goes; if it was in front, the IDE selects its right neighbour (else its left one), as the editor does. */
    private fun closeLikeTheIde(id: String) {
        val at = tabRow.indexOf(id)
        if (at < 0) return
        tabRow.removeAt(at)
        if (front != id) return
        front = tabRow.getOrNull(at) ?: tabRow.getOrNull(at - 1)
        val neighbour = front ?: return
        if (own.userCaused()) attached?.tabSelected(neighbour)
    }

    override fun markEnding(tabs: List<ThreadTab>) {
        calls += "ending " + tabs.joinToString(",") { it.id }
    }

    override fun clearEnding(threadIds: Collection<String>) {
        calls += "clear " + threadIds.joinToString(",")
    }

    override fun sessionRunning(tab: ThreadTab): Boolean = tab.id !in notRunning && tab.id !in notStarted

    override fun sessionStarted(tab: ThreadTab): Boolean = tab.id !in notStarted

    override suspend fun awaitEnded(tab: ThreadTab) {
        calls += "await ${tab.id}"
        ends(tab)
    }

    override suspend fun tabsInView(): TabsInView = TabsInView(inView, inFront = front)

    override fun refreshPresentation(threadId: String) = Unit

    override fun rename(threadId: String, title: String) = Unit

    override fun setListener(listener: ThreadTerminalListener) {
        attached = listener
    }
}
