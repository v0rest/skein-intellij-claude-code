package io.github.v0rest.skein.ui

import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState

/** A run of rows under one header; a null [title] means no header. */
data class Section(val title: String?, val threads: List<SkeinThread>)

object ThreadSections {
    /**
     * Splits [threads] (already in display order) into the sections the list shows. Grouped, a pinned thread sits only under "Pinned";
     * the order inside each section is the order it came in. [onlyWaiting] keeps the needs-you and your-turn threads and every pinned one.
     */
    fun of(threads: List<SkeinThread>, grouped: Boolean, onlyWaiting: Boolean): List<Section> {
        val kept = if (onlyWaiting) threads.filter { it.pinned || it.state.isWaiting() } else threads
        if (!grouped) return if (kept.isEmpty()) emptyList() else listOf(Section(null, kept))
        val titled = listOf(
            "Pinned" to kept.filter { it.pinned },
            "Needs you" to kept.filter { !it.pinned && it.state is ThreadState.NeedsYou },
            "Your turn" to kept.filter { !it.pinned && it.state is ThreadState.YourTurn },
            "Working" to kept.filter { !it.pinned && it.state.isWorking() },
            "Other" to kept.filter { !it.pinned && !it.state.isWaiting() && !it.state.isWorking() },
        )
        return titled.filter { it.second.isNotEmpty() }.map { Section(it.first, it.second) }
    }

    /** Thread id to the title of the section that thread opens, for the rows that carry a header. */
    fun headers(sections: List<Section>): Map<String, String> =
        sections.mapNotNull { s -> s.title?.let { title -> s.threads.firstOrNull()?.let { it.id to title } } }.toMap()

    private fun ThreadState.isWaiting() = this is ThreadState.NeedsYou || this is ThreadState.YourTurn

    private fun ThreadState.isWorking() =
        this == ThreadState.Working || this == ThreadState.WorkingUnknown || this == ThreadState.Starting || this == ThreadState.Background
}
