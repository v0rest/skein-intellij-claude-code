package io.github.v0rest.skein.ui

/**
 * What the empty thread list says. While a project nobody trusted yet keeps its saved threads back, an empty list would
 * look as if they were gone, so it says how many come back once the user trusts the project.
 */
object EmptyThreadsText {
    fun of(waiting: Int): String = when {
        waiting <= 0 -> "No threads yet"
        waiting == 1 -> "1 thread comes back once you trust the project"
        else -> "$waiting threads come back once you trust the project"
    }
}
