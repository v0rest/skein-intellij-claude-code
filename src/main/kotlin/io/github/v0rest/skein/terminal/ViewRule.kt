package io.github.v0rest.skein.terminal

/**
 * When the user is looking at a thread: the IDE is the active application, focus is in a window of this project
 * (the main frame or a detached editor window), and the thread's tab is the selected editor of the focused editor
 * window. A thread shown in another split, or in another project's window, is not in view.
 */
object ViewRule {
    fun inView(appActive: Boolean, focusInThisProject: Boolean, selectedThreadOfFocusedWindow: String?): Set<String> =
        if (appActive && focusInThisProject && selectedThreadOfFocusedWindow != null) setOf(selectedThreadOfFocusedWindow) else emptySet()
}
