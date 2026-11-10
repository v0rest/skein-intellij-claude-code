package io.github.v0rest.skein.claude

import io.github.v0rest.skein.process.ProcessProbe
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/** The live `claude` processes as of one poll: status files whose process is alive and really is that session. */
data class SessionSnapshot(val sessions: Map<Long, SessionFile>, val unrecognized: Set<Long>) {
    companion object {
        val EMPTY = SessionSnapshot(emptyMap(), emptySet())
    }
}

/**
 * One pass over the `sessions/<pid>.json` files. A file is re-read only when its size or mtime changed, or when it was modified
 * in the last couple of seconds (two rewrites can share a millisecond); a half-written file keeps the last good
 * state; a file whose process is gone, or whose process is not the one the file describes, is left out.
 */
class SessionDirectoryWatcher(
    private val home: ClaudeHome,
    private val probe: ProcessProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val stamp: FileStamp, val parse: SessionFileParse)

    private val cache = HashMap<Long, Entry>()

    fun poll(): SessionSnapshot {
        val sessions = LinkedHashMap<Long, SessionFile>()
        val unrecognized = LinkedHashSet<Long>()
        val seen = HashSet<Long>()
        for (path in home.list(home.sessionsDir, "*.json")) {
            val pid = path.fileName.toString().removeSuffix(".json").toLongOrNull() ?: continue
            val stamp = home.stamp(path) ?: continue
            seen += pid
            val entry = try {
                refresh(pid, path, stamp)
            } catch (e: RuntimeException) {
                // one file Skein cannot read must never freeze the whole snapshot; its thread shows "status unknown"
                if (probe.isAlive(pid)) unrecognized += pid
                null
            } ?: continue
            when (val parse = entry.parse) {
                is SessionFileParse.Ok -> if (isLive(parse.file)) sessions[pid] = parse.file
                is SessionFileParse.Unrecognized -> if (probe.isAlive(pid)) unrecognized += pid
                SessionFileParse.Incomplete -> Unit
            }
        }
        cache.keys.retainAll(seen)
        return SessionSnapshot(sessions, unrecognized)
    }

    private fun refresh(pid: Long, path: Path, stamp: FileStamp): Entry? {
        val cached = cache[pid]
        val recentlyModified = clock() - stamp.modifiedMillis < RECENT_MILLIS
        if (cached != null && cached.stamp == stamp && !recentlyModified) return cached
        val text = home.readText(path, MAX_BYTES) ?: return cached
        val parse = when (val parsed = SessionFileParser.parse(text)) {
            SessionFileParse.Incomplete -> return cached
            is SessionFileParse.Ok -> if (parsed.file.pid == pid) parsed else SessionFileParse.Unrecognized("pid differs from the file name")
            is SessionFileParse.Unrecognized -> parsed
        }
        return Entry(stamp, parse).also { cache[pid] = it }
    }

    /**
     * Alive, and started when the file says. `startedAt` (epoch millis, zone-free) decides first: it must fall
     * shortly after the process start. Without it, `procStart` (a local-time string, which the shell may have
     * written in another zone than the IDE's) must match within two seconds. Without either, alive is enough.
     */
    private fun isLive(file: SessionFile): Boolean {
        if (!probe.isAlive(file.pid)) return false
        // the start-time check catches a pid reused by another program; when the clocks disagree (on Linux the
        // JDK's start instants drift after a suspend or an NTP step) a process that is claude still vouches for it
        return startMatches(file) || isClaude(probe.command(file.pid))
    }

    private fun startMatches(file: SessionFile): Boolean {
        val start = probe.startInstant(file.pid) ?: return true
        val startMillis = start.toEpochMilli()
        val startedAt = file.startedAt
        if (startedAt != null) return startedAt >= startMillis - START_TOLERANCE_MILLIS && startedAt <= startMillis + STARTUP_WINDOW_MILLIS
        val procStart = file.procStart?.let(::parseProcStart) ?: return true
        return abs(procStart.toEpochMilli() - startMillis) <= START_TOLERANCE_MILLIS
    }

    private fun parseProcStart(text: String): Instant? =
        try {
            LocalDateTime.parse(text, PROC_START).atZone(ZoneId.systemDefault()).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }

    companion object {
        /**
         * The process is Claude Code itself: its executable is the native `claude` (named claude, or a file in the
         * versions folder it installs), or node running the `claude` script. Only the executable and the script count,
         * never other arguments, so `vim ~/src/claude` or `tail -f claude-code.log` is not claude. Also how a worktree
         * lock Claude Code made tells its claude from another program ([WorktreeLock]).
         */
        fun isClaude(command: List<String>): Boolean {
            val executable = command.firstOrNull() ?: return false
            if (executable.substringAfterLast('/') == "claude" || "/claude/versions/" in executable) return true
            val script = command.getOrNull(1) ?: return false
            return executable.substringAfterLast('/').startsWith("node") && script.substringAfterLast('/') == "claude"
        }

        /** `ps`-style "Thu Nov  5 21:35:49 2026" in the local zone, which is what Claude Code writes. */
        private val PROC_START: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US)
        const val START_TOLERANCE_MILLIS = 2_000L
        const val STARTUP_WINDOW_MILLIS = 120_000L
        const val RECENT_MILLIS = 2_000L
        const val MAX_BYTES = 64 * 1024
    }
}
