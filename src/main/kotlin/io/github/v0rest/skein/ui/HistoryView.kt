package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.threads.SessionMarks
import java.nio.file.Path

/** One History row: the session, and whether it is pinned or marked done. */
data class HistoryRow(val entry: HistoryEntry, val pinned: Boolean, val done: Boolean)

/** How the History lists sessions: pinned first, done ones hidden, each group newest first. */
object HistoryView {
    fun arrange(entries: List<HistoryEntry>, marks: SessionMarks, showDone: Boolean): List<HistoryRow> {
        val stale = staleDone(entries, marks)
        return entries
            .map { HistoryRow(it, it.sessionId in marks.pinned, it.sessionId in marks.done && it.sessionId !in stale) }
            .filter { showDone || !it.done }
            .sortedWith(compareByDescending<HistoryRow> { it.pinned }.thenByDescending { it.entry.lastActivity })
    }

    /** Sessions marked done that saw activity after the mark: they show again, and the mark is dropped. */
    fun staleDone(entries: List<HistoryEntry>, marks: SessionMarks): Set<String> =
        entries.filter { entry -> marks.done[entry.sessionId]?.let { entry.lastActivity > it } == true }.mapTo(HashSet()) { it.sessionId }

    /**
     * The marks after a scan: each entry takes over the marks of its earlier parts, done marks that saw newer activity
     * are dropped, and marks of sessions neither listed nor in [keep] (held by a thread, or still on disk) are pruned.
     * With [scanned] false (no folders to scan yet, or a listing that did not complete) nothing is pruned: an empty
     * result then says nothing. Only the marks in [examined], the ones that existed when [keep] was sampled, can go;
     * a mark made since then waits for the next scan.
     */
    fun reconcile(entries: List<HistoryEntry>, marks: SessionMarks, keep: Set<String>, scanned: Boolean, examined: Set<String>): SessionMarks {
        val followed = entries.fold(marks) { m, e -> m.follow(e.sessionId, earlierIds(e)) }
        val cleared = followed.undone(staleDone(entries, followed))
        return if (scanned) cleared.prune(entries.mapTo(HashSet()) { it.sessionId }, keep, examined) else cleared
    }

    /**
     * Which of the marked sessions the scan did not list ([missing]) keep their marks because their transcript is
     * still on disk. An [onDisk] listing that is null (could not be read) or empty while marks exist is unknown, not
     * proof of absence: then every missing one is kept for this round.
     */
    fun keptAlive(missing: Set<String>, onDisk: Set<String>?): Set<String> =
        if (onDisk.isNullOrEmpty()) missing else missing.filterTo(HashSet()) { it in onDisk }

    /** The session ids of the hidden earlier parts of [entry]'s conversation, from their transcript file names. */
    fun earlierIds(entry: HistoryEntry): List<String> = entry.earlierParts.map(::sessionIdOf)

    fun sessionIdOf(transcript: Path): String = transcript.fileName.toString().removeSuffix(".jsonl")
}

/**
 * How the eye in the History header shows whether done sessions are listed, by its icon and its tooltip as well as the
 * toolbar's pressed background: the eye in the accent colour while they are, a crossed-out eye while they are hidden.
 */
object DoneEye {
    enum class Look { SHOWN, HIDDEN }

    data class Presentation(val look: Look, val tooltip: String)

    fun of(showDone: Boolean): Presentation =
        if (showDone) Presentation(Look.SHOWN, "Showing done sessions") else Presentation(Look.HIDDEN, "Done sessions hidden")
}

/** The words of the History list. */
object HistoryText {
    /** What an empty list says: the sessions are gone, or they are all there but marked done and hidden. */
    fun empty(scannedCount: Int, shownCount: Int): String =
        if (scannedCount > 0 && shownCount == 0) "All past sessions are marked done" else "No past sessions in this project"
}
