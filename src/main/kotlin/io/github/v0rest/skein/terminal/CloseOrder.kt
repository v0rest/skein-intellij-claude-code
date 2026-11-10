package io.github.v0rest.skein.terminal

/**
 * The order a batch of thread tabs closes in. Closing the tab in front lets the IDE select a neighbour, which Skein
 * takes as the user opening that thread (a restored one would start `claude --resume`, a finished turn would count as
 * read), and in a batch that neighbour could be the next tab to close; so the tab in front goes last.
 */
object CloseOrder {
    /** [tabs] in their order, with the one whose thread is [front] (the selected editor's, if any) moved to the end. */
    fun <T : ThreadTab> frontLast(tabs: List<T>, front: String?): List<T> = tabs.filter { it.id != front } + tabs.filter { it.id == front }

    /**
     * Closes [tabs] one by one with [close], the one in front last, as Skein's own selections ([own]): each tab that
     * goes makes the IDE select a neighbour, which is not the user opening that thread (a restored one would type
     * `claude --resume`), not even for a moment after the batch.
     */
    inline fun <T : ThreadTab> closeInOrder(tabs: List<T>, front: String?, own: OwnSelections, close: (T) -> Unit) {
        own.begin()
        try {
            for (tab in frontLast(tabs, front)) close(tab)
        } finally {
            own.end()
        }
    }
}
