package io.github.v0rest.skein.terminal

/**
 * Which files of a close Skein said no to it closes itself right away. Skein says no to a close (a Close All, Close
 * Others, Close Unmodified) that takes in running thread tabs, to end those threads first, and the IDE then keeps every
 * file of that close open. So that one click still closes everything, Skein closes the other files of that very close
 * itself, as the IDE would have; the thread tabs close after their ending.
 */
object CloseReplay {
    /**
     * The files of [request] (the files the IDE asked about, in its order) that are not thread tabs and are open in the
     * project's one editor window. Nothing when the project has any other number of editor windows: the IDE does not
     * say which window the close was for, and closing a file in a window it was not meant for would close the wrong
     * tab. A file of the request that is not open there any more ([openInTheWindow]) is left alone.
     */
    fun <F> filesToReplay(request: List<F>, isThreadTab: (F) -> Boolean, editorWindows: Int, openInTheWindow: (F) -> Boolean): List<F> {
        if (editorWindows != 1) return emptyList()
        return request.distinct().filter { !isThreadTab(it) && openInTheWindow(it) }
    }
}
