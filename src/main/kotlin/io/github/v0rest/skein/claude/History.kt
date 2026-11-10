package io.github.v0rest.skein.claude

import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The folder name Claude Code derives from a working directory: every character outside `[A-Za-z0-9]` becomes `-`. */
object Slug {
    fun of(path: String): String = buildString(path.length) {
        for (c in path) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '-')
    }
}

/** A session's title in Claude Code's own order: `/rename` name, then AI title, then agent name, then the last prompt. */
object TranscriptTitle {
    private const val MAX_PROMPT_TITLE = 120

    /** A title and its rank in that order: 0 `/rename`, 1 AI title, 2 agent name, 3 last prompt. */
    data class Ranked(val rank: Int, val title: String)

    fun fromLines(lines: List<String>): String? = ranked(lines)?.title

    /** The best title one chunk of a transcript offers; within a rank the latest entry wins. */
    fun ranked(lines: List<String>): Ranked? {
        var custom: String? = null
        var ai: String? = null
        var agent: String? = null
        var prompt: String? = null
        for (obj in lines.asSequence().mapNotNull(Json::parseObjectOrNull)) {
            when (obj.stringOrNull("type")) {
                "custom-title" -> obj.stringOrNull("customTitle")?.takeIf { it.isNotBlank() }?.let { custom = it.trim() }
                "ai-title" -> obj.stringOrNull("aiTitle")?.takeIf { it.isNotBlank() }?.let { ai = it.trim() }
                "agent-name" -> obj.stringOrNull("agentName")?.takeIf { it.isNotBlank() }?.let { agent = it.trim() }
                "last-prompt" -> obj.stringOrNull("lastPrompt")?.let(::promptTitle)?.let { prompt = it }
            }
        }
        return custom?.let { Ranked(0, it) } ?: ai?.let { Ranked(1, it) } ?: agent?.let { Ranked(2, it) } ?: prompt?.let { Ranked(3, it) }
    }

    /** The better of a later chunk's title and an earlier one's: the lower rank wins, the later chunk wins a tie. */
    fun best(later: Ranked?, earlier: Ranked?): String? = when {
        later == null -> earlier?.title
        earlier == null -> later.title
        earlier.rank < later.rank -> earlier.title
        else -> later.title
    }

    /** The first non-blank line, cut to [MAX_PROMPT_TITLE] code points so no character is split. */
    private fun promptTitle(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (line.codePointCount(0, line.length) <= MAX_PROMPT_TITLE) return line
        return line.substring(0, line.offsetByCodePoints(0, MAX_PROMPT_TITLE))
    }
}

/** The working directory a transcript was recorded in: the first entry carrying a non-blank `cwd`. */
object TranscriptCwd {
    private val CWD_FIELD = Regex("\"cwd\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun fromLines(lines: List<String>): String? =
        lines.asSequence().mapNotNull(Json::parseObjectOrNull).mapNotNull { it.stringOrNull("cwd") }.firstOrNull { it.isNotBlank() }

    /**
     * The first `cwd` field in raw text, for a chunk whose first entry is longer than the chunk (a huge first prompt).
     * Older transcripts carry `cwd` before the message, so it is there even when the line is cut; current ones carry it
     * after the message, which leaves the cwd to [fromTail].
     */
    fun fromChunk(text: String): String? =
        CWD_FIELD.findAll(text).mapNotNull { m -> Json.parseOrNull("\"" + m.groupValues[1] + "\"")?.takeIf { it.isJsonPrimitive }?.asString }
            .firstOrNull { it.isNotBlank() }

    /**
     * The cwd of a transcript whose head holds none, from the entries at its end: the latest one that belongs to the
     * folder the transcript is stored in ([folderSlug]), so a later `cd` in a shell does not move the session; failing
     * that, the latest one at all.
     */
    fun fromTail(lines: List<String>, folderSlug: String): String? {
        val cwds = latestFirst(lines)
        return cwds.firstOrNull { Slug.of(it) == folderSlug } ?: cwds.firstOrNull()
    }

    /** The latest cwd in [lines] that belongs to the folder the transcript is stored in ([folderSlug]), if any. */
    fun latestInFolder(lines: List<String>, folderSlug: String): String? = latestFirst(lines).firstOrNull { Slug.of(it) == folderSlug }

    private fun latestFirst(lines: List<String>): List<String> =
        lines.asReversed().mapNotNull { Json.parseObjectOrNull(it)?.stringOrNull("cwd")?.takeIf(String::isNotBlank) }
}

/** Where a session moved to: transcripts record a `relocated` entry with the new `relocatedCwd`; the latest one counts. */
object TranscriptRelocation {
    fun fromLines(lines: List<String>): String? =
        lines.asReversed().asSequence().mapNotNull(Json::parseObjectOrNull)
            .filter { it.stringOrNull("type") == "relocated" }
            .mapNotNull { it.stringOrNull("relocatedCwd")?.takeIf(String::isNotBlank) }
            .firstOrNull()
}

/**
 * Sessions a program ran on its own: a session typed into a terminal records the entrypoint `cli`, one the Agent SDK
 * started records `sdk-py` or another `sdk-` value. History leaves a session out only when it reads `sdk-` at both
 * ends it records one, so automated runs do not bury the owner's sessions, and a run the owner went on with by hand
 * stays listed.
 */
object ScriptedSessions {
    private const val SDK_PREFIX = "sdk-"

    /** True when the entrypoint recorded first in [head] and the latest one in [tail], whichever exist, are SDK ones. */
    fun isScripted(head: List<String>, tail: List<String>): Boolean {
        val ends = listOfNotNull(firstEntrypoint(head), firstEntrypoint(tail.asReversed()))
        return ends.isNotEmpty() && ends.all { it.startsWith(SDK_PREFIX) }
    }

    private fun firstEntrypoint(lines: List<String>): String? =
        lines.asSequence().mapNotNull(Json::parseObjectOrNull).mapNotNull { it.stringOrNull("entrypoint") }.firstOrNull { it.isNotBlank() }
}

/** The session this conversation moved on to, from the last `continued-in` entry. */
object TranscriptContinuation {
    fun fromLines(lines: List<String>): String? =
        lines.asSequence().mapNotNull(Json::parseObjectOrNull)
            .filter { it.stringOrNull("type") == "continued-in" }
            .mapNotNull { it.stringOrNull("continuedInSessionId")?.takeIf { id -> id.isNotBlank() } }
            .lastOrNull()
}

/** A past session; [transcript] is where its transcript lies, known for every entry a scan found. */
data class HistoryEntry(
    val sessionId: String,
    val cwd: String,
    val title: String,
    val lastActivity: Long,
    val continuedIn: String?,
    val transcript: Path? = null,
    val earlierParts: List<Path> = emptyList(),
    /** The name of the worktree the session ran in, or null for a session in an ordinary folder. */
    val worktree: String? = null,
)

/** The live session with this entry's id, if one is running right now. */
fun HistoryEntry.liveIn(snapshot: SessionSnapshot): SessionFile? = snapshot.sessions.values.firstOrNull { it.sessionId == sessionId }

/**
 * The title the History shows for one session's transcript, without a scan: [TranscriptTitle] over the tail, and over
 * the head too when the tail holds nothing stronger than a last prompt and the file is longer than the tail. Bounded
 * reads only.
 */
object SessionTitles {
    /** The title of the transcript at [file], or null when it is missing, unreadable or holds none. Never throws on odd content. */
    fun read(home: ClaudeHome, file: Path): String? {
        val stamp = home.stamp(file) ?: return null
        val tail = home.readTail(file, HistoryScanner.TAIL_BYTES)?.let { linesOf(it, dropFirst = stamp.size > HistoryScanner.TAIL_BYTES) }.orEmpty()
        return of(home, file, stamp.size, tail)
    }

    /** The title from a transcript of [size] bytes whose tail lines are [tail]; the head is read only when it could hold a stronger title. */
    internal fun of(home: ClaudeHome, file: Path, size: Long, tail: List<String>): String? {
        val tailTitle = TranscriptTitle.ranked(tail)
        if ((tailTitle != null && tailTitle.rank <= 0) || size <= HistoryScanner.TAIL_BYTES) return tailTitle?.title
        // the tail may hold only a weaker title (a last prompt) while an early /rename sits near the start
        val head = home.readHead(file, HistoryScanner.TITLE_HEAD_BYTES)?.let { linesOf(it, dropLast = size > HistoryScanner.TITLE_HEAD_BYTES) }.orEmpty()
        return TranscriptTitle.best(later = tailTitle, earlier = TranscriptTitle.ranked(head))
    }

    /** Lines of a chunk; a chunk cut at either end drops the partial line there. */
    internal fun linesOf(bytes: ByteArray, dropFirst: Boolean = false, dropLast: Boolean = false): List<String> {
        var lines = String(bytes, Charsets.UTF_8).split('\n')
        if (dropFirst) lines = lines.drop(1)
        if (dropLast) lines = lines.dropLast(1)
        return lines
    }
}

/**
 * Past sessions under the project's folders, from the transcripts in `projects/`, without the runs a program started
 * ([ScriptedSessions]). Folder names are only a pre-filter (the slug is ambiguous); the `cwd` inside each transcript
 * decides, and a session that moved counts where it moved to. Reads per transcript are bounded and cached.
 */
class HistoryScanner(private val home: ClaudeHome) {
    private class Parsed(val stamp: FileStamp, val cwd: String?, val title: String?, val continuedIn: String?, val scripted: Boolean)

    /**
     * What a scan found. [complete] is true only when `projects/` and every project folder it holds were listed to the
     * end; an incomplete scan says nothing about the sessions it did not list, so no mark may be pruned on it.
     */
    data class Scan(val entries: List<HistoryEntry>, val complete: Boolean)

    private val cache = HashMap<Path, Parsed>()

    /** Entries whose `cwd` equals or lies under one of [roots], newest first, without sessions that continued elsewhere in the list. */
    fun scan(roots: List<String>): Scan {
        val rootPaths = roots.mapNotNull(::normalizedOrNull)
        val prefixes = rootPaths.map { Slug.of(it.toString()) }
        val listed = home.listOrNull(home.projectsDir)
        var complete = listed != null
        val folders = listed.orEmpty().filter { folder -> prefixes.any { folder.fileName.toString().startsWith(it) } }
        val entries = ArrayList<HistoryEntry>()
        val scripted = HashSet<String>()
        val seen = HashSet<Path>()
        for (folder in folders) {
            val files = home.listOrNull(folder, "*.jsonl")
            if (files == null) {
                complete = false
                continue
            }
            for (file in files) {
                // the file name becomes the id Skein types after `claude --resume`, so only UUID names are sessions
                if (!SESSION_FILE.matches(file.fileName.toString())) continue
                val stamp = home.stamp(file) ?: continue
                seen.add(file) // not `+=`: a Path is itself an Iterable<Path>, which makes `+=` ambiguous
                val parsed = cache[file]?.takeIf { it.stamp == stamp } ?: parse(file, stamp).also { cache[file] = it }
                val cwd = parsed.cwd ?: continue
                val cwdPath = normalizedOrNull(cwd) ?: continue
                if (rootPaths.none { cwdPath.startsWith(it) }) continue
                val sessionId = file.fileName.toString().removeSuffix(".jsonl")
                if (parsed.scripted) scripted.add(sessionId)
                entries += HistoryEntry(sessionId, cwd, parsed.title ?: sessionId, stamp.modifiedMillis, parsed.continuedIn, file, worktree = Worktrees.split(cwd)?.name)
            }
        }
        cache.keys.retainAll(seen)
        // a session that continued in another one stays hidden even when that one is a scripted run
        val ids = entries.mapTo(HashSet()) { it.sessionId }
        val visible = entries.filter { it.sessionId !in scripted && (it.continuedIn == null || it.continuedIn !in ids) }
        val parts = earlierParts(entries, ids, visible.mapTo(HashSet()) { it.sessionId })
        val sorted = visible.map { entry -> parts[entry.sessionId]?.let { entry.copy(earlierParts = it) } ?: entry }
            .sortedByDescending { it.lastActivity }
        return Scan(sorted, complete)
    }

    /**
     * For each listed entry, the transcripts of the hidden sessions whose conversation continued into it, along the
     * whole chain: the row stands for them.
     */
    private fun earlierParts(entries: List<HistoryEntry>, listed: Set<String>, visible: Set<String>): Map<String, List<Path>> {
        val byId = entries.associateBy { it.sessionId }
        val parts = HashMap<String, MutableList<Path>>()
        for (entry in entries) {
            val transcript = entry.transcript ?: continue
            if (entry.sessionId in visible || entry.continuedIn == null || entry.continuedIn !in listed) continue
            val seen = hashSetOf(entry.sessionId)
            var next = byId[entry.continuedIn]
            while (next != null && next.sessionId !in visible && seen.add(next.sessionId)) next = next.continuedIn?.let(byId::get)
            if (next != null && next.sessionId in visible) parts.getOrPut(next.sessionId) { ArrayList() }.add(transcript)
        }
        return parts
    }

    private fun parse(file: Path, stamp: FileStamp): Parsed {
        val headBytes = home.readHead(file, CWD_HEAD_BYTES)
        val head = headBytes?.let { linesOf(it, dropLast = stamp.size > CWD_HEAD_BYTES) }.orEmpty()
        val tail = home.readTail(file, TAIL_BYTES)?.let { linesOf(it, dropFirst = stamp.size > TAIL_BYTES) }.orEmpty()
        val folderSlug = file.parent.fileName.toString()
        val headCwd = TranscriptCwd.fromLines(head) ?: headBytes?.let { TranscriptCwd.fromChunk(String(it, Charsets.UTF_8)) }
        // a session that moved counts where it moved to: its latest recorded move, or, when the move is further back
        // than the tail, the latest cwd that belongs to the folder the transcript sits in now; with no cwd in the head
        // at all (a first entry longer than the head) the tail still has one
        val cwd = TranscriptRelocation.fromLines(tail)
            ?: headCwd?.let { if (Slug.of(it) == folderSlug) it else TranscriptCwd.latestInFolder(tail, folderSlug) ?: it }
            ?: TranscriptCwd.fromTail(tail, folderSlug)
        val title = SessionTitles.of(home, file, stamp.size, tail)
        return Parsed(stamp, cwd, title, TranscriptContinuation.fromLines(tail), ScriptedSessions.isScripted(head, tail))
    }

    private fun linesOf(bytes: ByteArray, dropFirst: Boolean = false, dropLast: Boolean = false): List<String> =
        SessionTitles.linesOf(bytes, dropFirst, dropLast)

    /** The physical path when it exists: Claude Code's cwd (and so its folder slug) comes from getcwd(), which resolves symlinks. */
    private fun normalizedOrNull(path: String): Path? =
        try {
            val p = Path.of(path).toAbsolutePath().normalize()
            try {
                p.toRealPath()
            } catch (e: IOException) {
                p
            }
        } catch (e: InvalidPathException) {
            null
        }

    companion object {
        private val SESSION_FILE = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\.jsonl$")
        const val CWD_HEAD_BYTES = 64 * 1024
        const val TAIL_BYTES = 256 * 1024
        const val TITLE_HEAD_BYTES = 256 * 1024
    }
}
