package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.FileStamp
import io.github.v0rest.skein.notify.NotificationContent

/** What a thread did last, in one line under its name: the last answer's first sentence, the tool or question it waits on, or what it runs now. */
object ThreadPreviews {
    /** The preview for a thread in [state] from its transcript tail ([lines], oldest first); null when nothing usable. */
    fun previewOf(state: ThreadState, lines: List<String>): String? = when (state) {
        is ThreadState.NeedsYou -> NotificationContent.fromTail(lines, NotificationKind.NEEDS_YOU, state.reason)
        is ThreadState.YourTurn, ThreadState.Idle -> NotificationContent.fromTail(lines, NotificationKind.TURN_FINISHED, null)
        ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Starting -> NotificationContent.working(lines)
        ThreadState.NotStarted, is ThreadState.RunningElsewhere, ThreadState.Exited, ThreadState.Unknown -> null
    }

    /** States with a preview at all, as [previewOf] has them: for the others nothing is read. */
    internal fun shown(state: ThreadState): Boolean = when (state) {
        is ThreadState.NeedsYou, is ThreadState.YourTurn, ThreadState.Idle -> true
        ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Starting -> true
        ThreadState.NotStarted, is ThreadState.RunningElsewhere, ThreadState.Exited, ThreadState.Unknown -> false
    }

    /** The states [previewOf] shows [NotificationContent.working] for: claude writes its transcript all the time then. */
    internal fun working(state: ThreadState): Boolean = when (state) {
        ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Starting -> true
        is ThreadState.NeedsYou, is ThreadState.YourTurn, ThreadState.Idle -> false
        ThreadState.NotStarted, is ThreadState.RunningElsewhere, ThreadState.Exited, ThreadState.Unknown -> false
    }
}

/**
 * The previews of the threads, reading a transcript tail ([tail]) only when it may say something new: its stamp
 * ([read]) changed, or the thread's state did (the state or the time it was entered). A working thread writes on
 * and on, so its transcript is read at most every [WORKING_READ_MILLIS]; a state change is read at once. Blocking,
 * and for one caller at a time: it keeps what it saw last without locks.
 */
class PreviewCache(
    private val read: (SkeinThread) -> FileStamp?,
    private val tail: (SkeinThread) -> List<String>,
    private val now: () -> Long,
) {
    private class Seen(
        val state: ThreadState,
        val stateSince: Long,
        val stamp: FileStamp?,
        val readAt: Long,
        val preview: String?,
        val context: ContextUse?,
        val sessionId: String?,
    )

    /** What one [refresh] found: the preview lines and the model and context in use, each by thread id. */
    class Previewed(val lines: Map<String, String>, val contexts: Map<String, ContextUse>)

    private var seen: Map<String, Seen> = emptyMap()

    /** Thread id to preview for [threads]; a thread with nothing usable has no entry, and a thread no longer listed is forgotten. */
    fun update(threads: List<SkeinThread>): Map<String, String> = refresh(threads).lines

    /**
     * [update] with the model and context in use as well, both from the one tail read; a thread with none has no
     * entry.
     */
    fun refresh(threads: List<SkeinThread>): Previewed {
        val time = now()
        val next = HashMap<String, Seen>(threads.size)
        val previews = LinkedHashMap<String, String>()
        val contexts = LinkedHashMap<String, ContextUse>()
        for (thread in threads) {
            val entry = next(thread, seen[thread.id], time)
            next[thread.id] = entry
            entry.preview?.let { previews[thread.id] = it }
            entry.context?.let { contexts[thread.id] = it }
        }
        seen = next
        return Previewed(previews, contexts)
    }

    private fun next(thread: SkeinThread, last: Seen?, time: Long): Seen {
        val state = thread.state
        if (!ThreadPreviews.shown(state)) return Seen(state, thread.stateSince, null, time, null, null, thread.sessionId)
        val stamp = read(thread)
        if (last != null && last.state == state && last.stateSince == thread.stateSince) {
            if (stamp == last.stamp) return last
            // the stamp kept is the one of the last read, so a transcript that moved on is read once the wait is over
            if (ThreadPreviews.working(state) && time - last.readAt < WORKING_READ_MILLIS) return last
        }
        val lines = if (stamp == null) null else tail(thread)
        val context = when (val scan = lines?.let(ContextUse::scan)) {
            is ContextUse.Scan.Found -> scan.use
            ContextUse.Scan.Reset, null -> null
            // the answer dropped out of the tail window: still the value of the same session, never of another one
            ContextUse.Scan.NothingInTail -> last?.context?.takeIf { thread.sessionId != null && last.sessionId == thread.sessionId }
        }
        return Seen(state, thread.stateSince, stamp, time, lines?.let { ThreadPreviews.previewOf(state, it) }, context, thread.sessionId)
    }

    companion object {
        const val WORKING_READ_MILLIS = 5_000L
    }
}
