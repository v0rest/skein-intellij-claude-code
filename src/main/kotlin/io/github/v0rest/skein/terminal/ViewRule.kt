package io.github.v0rest.skein.terminal

/**
 * When the user is looking at a thread: the IDE is the active application, focus is in a window of this project
 * (the main frame or a detached editor window), and the thread's tab is either the selected editor of the focused editor
 * window or the selected tab of a pane of the Terminal tool window while that tool window is visible. A thread shown in
 * another split, or in another project's window, is not in view, and neither is one whose tab has not settled in its
 * place yet - Skein's own move into the editor selects it in the tool window and then in the editor for a moment, and a
 * tab that only flashes past that way is not looked at.
 */
object ViewRule {
    fun inView(
        appActive: Boolean,
        focusInThisProject: Boolean,
        selectedThreadOfFocusedWindow: String?,
        threadsSelectedInTerminalWindow: Set<String>,
        settled: Set<String>,
    ): Set<String> =
        if (!appActive || !focusInThisProject) {
            emptySet()
        } else {
            (threadsSelectedInTerminalWindow + listOfNotNull(selectedThreadOfFocusedWindow)).filterTo(LinkedHashSet()) { it in settled }
        }

    /**
     * The thread the list marks as the one in front, focus or not: the selected editor's thread, else a thread selected
     * in the visible Terminal tool window, else none.
     */
    fun inFront(selectedEditorThread: String?, threadsSelectedInTerminalWindow: Set<String>): String? =
        selectedEditorThread ?: threadsSelectedInTerminalWindow.firstOrNull()
}
