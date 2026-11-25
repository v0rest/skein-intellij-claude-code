package io.github.v0rest.skein.terminal

/**
 * The order a batch of thread tabs closes in. Closing the tab in front lets the IDE show a neighbour. Inside Skein's own
 * close that is not the user opening it ([OwnSelections]), but a shown tab starts its shell, and a restored one left in
 * front a moment starts claude ([io.github.v0rest.skein.threads.InViewStart]) - in a batch that neighbour could be the
 * next tab to close. So the tab in front goes last, and the neighbour shown then is one that stays.
 */
object CloseOrder {
    /** [tabs] in their order, with the one whose thread is [front] (the selected editor's, if any) moved to the end. */
    fun <T : ThreadTab> frontLast(tabs: List<T>, front: String?): List<T> = tabs.filter { it.id != front } + tabs.filter { it.id == front }

    /**
     * Closes [tabs] one by one with [close], the one in front last, as Skein's own selections ([own]): each tab that
     * goes makes the IDE select a neighbour, which is not the user opening that thread, not even for a moment after the
     * batch - a restored neighbour starts `claude --resume` only once it stays in front a moment, as any tab in view does.
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
