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

    /**
     * What a move into the editor that failed leaves, and the [words] the log line about it ends with. The action may
     * have taken the tab out of the Terminal tool window before it failed, so only [STAYS] keeps it there as the setting
     * off would.
     */
    enum class AfterFailedMove(val words: String) {
        /** The Terminal tool window still lists the tab, so it stays there. */
        STAYS("it stays in the Terminal tool window"),

        /** An open editor file shows the tab all the same, and Skein marked it as the thread's. */
        IN_EDITOR("it is in the editor all the same"),

        /** In neither place yet - the next look of the thread service finds its editor tab. */
        LOOKS_AGAIN("it has left the Terminal tool window, and Skein looks for its editor tab again on its next look"),

        /** Its content was disposed meanwhile - the tab is closed, and so is the thread. */
        GONE("its tab is closed"),

        /** Where the tab is could not be told - the project closes, or the look failed - and nothing looks again for it. */
        UNTOLD("Skein cannot tell where its tab is now"),
    }

    /** [AfterFailedMove] for a tab that is [where] once the move failed, null when that could not be told. */
    fun afterFailedMove(where: Where?): AfterFailedMove = when (where) {
        Where.TERMINAL_WINDOW -> AfterFailedMove.STAYS
        Where.EDITOR -> AfterFailedMove.IN_EDITOR
        Where.GONE -> AfterFailedMove.GONE
        Where.MOVING -> AfterFailedMove.LOOKS_AGAIN
        null -> AfterFailedMove.UNTOLD
    }
}
