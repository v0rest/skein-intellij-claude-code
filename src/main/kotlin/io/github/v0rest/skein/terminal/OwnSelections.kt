package io.github.v0rest.skein.terminal

/**
 * Tells the user's own tab selections from the ones Skein causes itself. Opening, moving, reselecting and closing tabs
 * make the IDE select and focus tabs, and Skein must not take that as the user opening a thread: a restored one would
 * start `claude --resume`, a finished turn would count as read. Those events arrive a little after the operation that
 * caused them, so they are ignored for [quietMillis] after it too. [begin] and [end] pair up and may nest. EDT only.
 */
class OwnSelections(private val quietMillis: Long, private val clock: () -> Long) {
    private var depth = 0

    @Volatile
    private var quietUntil = 0L

    fun begin() {
        depth++
    }

    fun end() {
        depth--
        quietUntil = clock() + quietMillis
    }

    fun userCaused(): Boolean = depth == 0 && clock() >= quietUntil
}
