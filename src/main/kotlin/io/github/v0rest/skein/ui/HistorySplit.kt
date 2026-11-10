package io.github.v0rest.skein.ui

import javax.swing.JComponent

/**
 * The threads on top and the history below, laid out by a [SectionSplit] whose divider the IDE remembers under
 * [PROPORTION_KEY]. Whether the history was left open is remembered too, under [OPEN_KEY], for the next start.
 */
class HistorySplit(threads: JComponent, history: JComponent, private val memory: Memory = IdeMemory) {
    /** Where the open or closed history is kept between IDE starts. */
    interface Memory {
        var historyOpen: Boolean
    }

    private object IdeMemory : Memory {
        private val ide = SectionSplit.IdeOpenMemory(OPEN_KEY)
        override var historyOpen: Boolean by ide::open
    }

    private val split = SectionSplit(
        threads,
        history,
        PROPORTION_KEY,
        DEFAULT_PROPORTION,
        object : SectionSplit.OpenMemory {
            override var open: Boolean by memory::historyOpen
        },
    )

    val component: JComponent get() = split.component

    /** Whether the history was open when the IDE last showed it. */
    val wasOpen: Boolean get() = split.wasOpen

    /** Lays the panel out for an open or closed history and remembers which one it is. */
    fun show(open: Boolean): Unit = split.show(open)

    companion object {
        const val PROPORTION_KEY = "Skein.History.Proportion"
        const val OPEN_KEY = "Skein.History.Open"

        /** The threads' share of the height when the history first opens, before the owner drags the divider. */
        const val DEFAULT_PROPORTION = 0.5f
    }
}
