package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
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

/** What a process's command tells about it being Claude Code's CLI ([SessionDirectoryWatcher.identify]). */
enum class ClaudeIdentity {
    /** Claude Code's CLI, by its executable, or node running its script. */
    CLI,

    /** Another program, and the command says enough to know it. */
    OTHER,

    /**
     * The command does not tell: the system gave none (another user's process, a race), or it is node and its script
     * cannot tell, which is always so on Windows, where bun and deno count like node. No proof either way.
     */
    UNKNOWN,
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
    private val style: PathStyle = PathStyle.current,
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
     * shortly after the process start. Without it, `procStart` must match within two seconds - a local-time string on
     * macOS and Linux, which the shell may have written in another zone than the IDE's, a FILETIME on Windows
     * ([procStartInstant]). Without either, alive is enough.
     */
    private fun isLive(file: SessionFile): Boolean {
        if (!probe.isAlive(file.pid)) return false
        // the start-time check catches a pid reused by another program; when the clocks disagree (on Linux the
        // JDK's start instants drift after a suspend or an NTP step) a process that is claude still vouches for it.
        // Only the CLI vouches - a command the system did not tell proves nothing, so then the start decides alone
        return startMatches(file) || identify(probe.command(file.pid), style) == ClaudeIdentity.CLI
    }

    private fun startMatches(file: SessionFile): Boolean {
        val start = probe.startInstant(file.pid) ?: return true
        val startMillis = start.toEpochMilli()
        val startedAt = file.startedAt
        if (startedAt != null) return startedAt >= startMillis - START_TOLERANCE_MILLIS && startedAt <= startMillis + STARTUP_WINDOW_MILLIS
        val procStart = file.procStart?.let { procStartInstant(it, file.pidDomain) } ?: return true
        return abs(procStart.toEpochMilli() - startMillis) <= START_TOLERANCE_MILLIS
    }

    companion object {
        /**
         * What [command] (a process's executable, then its arguments) says about being Claude Code's CLI. The CLI's
         * executable is the native claude - named `claude`, or on Windows `claude.exe` in any case, or a file below the
         * `claude/versions` folder it installs - or node running the `claude` script. Only the executable and the script
         * count, never other arguments, so `vim ~/src/claude` or `tail -f claude-code.log` is not claude. Also how a
         * worktree lock Claude Code made tells its claude from another program ([WorktreeLock]).
         *
         * Windows reports the full executable path and never the arguments (JDK's ProcessHandle, checked on Windows
         * 11), so node there is [ClaudeIdentity.UNKNOWN], and so are bun and deno, which can run the claude script as
         * well - a guess either way would drop a live thread or call a held lock stale.
         */
        fun identify(command: List<String>, style: PathStyle = PathStyle.current): ClaudeIdentity {
            val executable = command.firstOrNull()?.takeIf { it.isNotBlank() } ?: return ClaudeIdentity.UNKNOWN
            val names = PathRules.segments(executable, style)
            val name = names.lastOrNull() ?: return ClaudeIdentity.UNKNOWN
            if (isBelowVersions(names, style)) return ClaudeIdentity.CLI
            if (isClaudeName(name, style)) return if (isInClaudeDesktop(names, style)) ClaudeIdentity.OTHER else ClaudeIdentity.CLI
            if (!isScriptHost(name, style)) return ClaudeIdentity.OTHER
            val script = command.getOrNull(1)
            if (script != null && PathRules.sameName(PathRules.name(script, style), "claude", style)) return ClaudeIdentity.CLI
            // npm's claude.cmd starts node with cli.js, so on Windows even a script Skein could see would prove nothing
            return if (script == null || style == PathStyle.WINDOWS) ClaudeIdentity.UNKNOWN else ClaudeIdentity.OTHER
        }

        /** A file somewhere below a `claude/versions` folder, where the native installer keeps each version. */
        private fun isBelowVersions(names: List<String>, style: PathStyle): Boolean =
            (0 until names.size - 2).any { PathRules.sameName(names[it], "claude", style) && PathRules.sameName(names[it + 1], "versions", style) }

        /**
         * Claude Desktop's processes are claude.exe too, in its package folder `Claude_<version>_<arch>__<id>` right
         * under a WindowsApps folder (C:\Program Files\WindowsApps, and its app execution alias folder has the same
         * shape), so on Windows the name says nothing about the CLI there. Any other WindowsApps folder is no sign.
         */
        private fun isInClaudeDesktop(names: List<String>, style: PathStyle): Boolean =
            style == PathStyle.WINDOWS &&
                (0 until names.size - 2).any { PathRules.sameName(names[it], WINDOWS_APPS, style) && names[it + 1].startsWith(DESKTOP_PACKAGE, ignoreCase = true) }

        /** node, and on Windows also bun and deno - the programs that can run the claude script. */
        private fun isScriptHost(name: String, style: PathStyle): Boolean = when (style) {
            PathStyle.UNIX -> name.startsWith("node")
            PathStyle.WINDOWS -> name.startsWith("node", ignoreCase = true) || WINDOWS_SCRIPT_HOSTS.any { PathRules.sameName(name, it, style) }
        }

        private fun isClaudeName(name: String, style: PathStyle): Boolean = when (style) {
            PathStyle.UNIX -> name == "claude"
            PathStyle.WINDOWS -> PathRules.sameName(name, "claude.exe", style) || PathRules.sameName(name, "claude", style)
        }

        /**
         * The instant [procStart] names, null when it cannot be read. Windows writes a FILETIME - 100-ns ticks since
         * 1601-01-01 UTC, in decimal ("134390832698011131") - read as one when the text is all digits and at least
         * [FILETIME_MIN_DIGITS] long, or when [pidDomain] says the file comes from Windows. Anything else is `ps`'s
         * local-time text, read in [zone].
         */
        fun procStartInstant(procStart: String, pidDomain: String?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
            val digits = procStart.isNotEmpty() && procStart.all { it in '0'..'9' }
            if (digits && (procStart.length >= FILETIME_MIN_DIGITS || pidDomain?.startsWith(WINDOWS_PID_DOMAIN) == true)) {
                val ticks = procStart.toLongOrNull() ?: return null
                return Instant.ofEpochSecond(ticks / TICKS_PER_SECOND - FILETIME_EPOCH_SECONDS, ticks % TICKS_PER_SECOND * NANOS_PER_TICK)
            }
            return try {
                LocalDateTime.parse(procStart, PROC_START).atZone(zone).toInstant()
            } catch (e: DateTimeParseException) {
                null
            }
        }

        /** `ps`-style "Thu Nov  5 21:35:49 2026" in the local zone, which is what Claude Code writes on macOS and Linux. */
        private val PROC_START: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US)

        /** Every FILETIME after 1632 has this many digits; a shorter number counts as one only with a Windows pidDomain. */
        const val FILETIME_MIN_DIGITS = 17
        private const val WINDOWS_PID_DOMAIN = "win32:"
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val NANOS_PER_TICK = 100L

        /** Seconds from 1601-01-01, where a FILETIME counts from, to 1970-01-01. */
        private const val FILETIME_EPOCH_SECONDS = 11_644_473_600L
        private const val WINDOWS_APPS = "WindowsApps"
        private const val DESKTOP_PACKAGE = "Claude_"
        private val WINDOWS_SCRIPT_HOSTS = listOf("bun.exe", "deno.exe")
        const val START_TOLERANCE_MILLIS = 2_000L
        const val STARTUP_WINDOW_MILLIS = 120_000L
        const val RECENT_MILLIS = 2_000L
        const val MAX_BYTES = 64 * 1024
    }
}
