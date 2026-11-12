package io.github.v0rest.skein.terminal

/**
 * Where a thread's terminal tab is now. The terminal plugin moves one and the same tab between the Terminal tool window
 * and the editor (Move to Editor, a drag either way), closing an editor file or removing a tool window content on the
 * way without ending anything; the tab is closed for good only when the IDE disposes its content, which ends its
 * terminal. So an editor file that closed, or a content that left the tool window, says nothing on its own.
 */
object TabPlaces {
    enum class Where {
        /** The Terminal tool window lists the tab. */
        TERMINAL_WINDOW,

        /** An open editor file shows the tab. */
        EDITOR,

        /** Alive but in neither place: on its way from one to the other. */
        MOVING,

        /** Its content was disposed: closed for good. */
        GONE,
    }

    /**
     * [disposed]: the tab's content was disposed. [inTerminalWindow]: the Terminal tool window lists the tab.
     * [editorFileOpen]: an open editor file shows it. Null while the project closes, when nothing is reported.
     */
    fun where(projectClosing: Boolean, disposed: Boolean, inTerminalWindow: Boolean, editorFileOpen: Boolean): Where? = when {
        projectClosing -> null
        disposed -> Where.GONE
        inTerminalWindow -> Where.TERMINAL_WINDOW
        editorFileOpen -> Where.EDITOR
        else -> Where.MOVING
    }
}
