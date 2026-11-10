package io.github.v0rest.skein.claude

import io.github.v0rest.skein.process.ProcessProbe
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The lock Claude Code puts on a worktree it makes, as `git worktree list --porcelain` shows its reason, all of it:
 * `claude session <session> (pid <pid> start <start>)`, where [start] is when that claude started, written as `ps`
 * writes it ("Tue Nov 10 08:30:41 2026"), and seen written in UTC. Seen from outside, as data git shows; Claude Code
 * can leave the lock behind when its claude ends. Any other reason, odd spacing and a reason git had to quote included,
 * is a lock someone else made.
 */
data class ClaudeWorktreeLock(val session: String, val pid: Long, val start: String) {
    companion object {
        /** One space between the words, a name and a date with no spaces or brackets at their ends, a pid of up to ten digits. */
        private val SHAPE = Regex("""claude session ([^\s()]+) \(pid ([1-9][0-9]{0,9}) start ([^\s()]+(?: +[^\s()]+)*)\)""")

        /** `ps`'s start, its day padded or not; the day of the week must fit the date. */
        private const val START_PATTERN = "EEE MMM d HH:mm:ss uuuu"

        /** The lock when [reason] has exactly Claude Code's shape; null for any other reason, and for none. */
        fun parse(reason: String?): ClaudeWorktreeLock? {
            val match = SHAPE.matchEntire(reason ?: return null) ?: return null
            val (session, pid, start) = match.destructured
            return ClaudeWorktreeLock(session, pid.toLong(), start)
        }

        /**
         * The instants [text] can name in [zone]: one, or two in the hour the clocks go back, which happens twice; none
         * when it cannot be read, in English (what Claude Code was seen to write) or in the system's own language.
         */
        fun startsOf(text: String, zone: ZoneId): List<Instant> {
            val single = text.trim().replace(Regex(" +"), " ")
            for (locale in listOf(Locale.US, Locale.getDefault()).distinct()) {
                val local = try {
                    LocalDateTime.parse(single, DateTimeFormatter.ofPattern(START_PATTERN, locale).withResolverStyle(ResolverStyle.STRICT))
                } catch (_: DateTimeParseException) {
                    continue
                }
                val zoned = local.atZone(zone)
                return listOf(zoned.withEarlierOffsetAtOverlap().toInstant(), zoned.withLaterOffsetAtOverlap().toInstant()).distinct()
            }
            return emptyList()
        }
    }
}

/** What a worktree's lock means for its removal ([of]). */
sealed interface WorktreeLock {
    /** Not locked. */
    data object None : WorktreeLock

    /** Locked by someone else, or with no reason or one Skein does not know: git's refusal stands, as for any lock. */
    data object Foreign : WorktreeLock

    /**
     * A lock Claude Code left behind: the claude that made it has ended, its pid gone or held by a process that is
     * neither claude nor started then. Skein may lift it, after the owner's yes.
     */
    data class Stale(val lock: ClaudeWorktreeLock) : WorktreeLock

    /** Claude Code's lock, and its claude still runs, as process [pid]: the lock stays, and so does the worktree. */
    data class Live(val pid: Long) : WorktreeLock

    companion object {
        /** How far a process's start may lie from the start the lock names, which `ps` writes to the second. */
        const val START_TOLERANCE_MILLIS = SessionDirectoryWatcher.START_TOLERANCE_MILLIS

        /**
         * The lock of a worktree git lists as [locked] with [reason]. Claude Code's lock is stale when no process has its
         * pid, or when the process that has it is not that claude by both signs: its command, which the system told, is
         * not claude ([SessionDirectoryWatcher.isClaude]), and its start, which the system told too, lies more than
         * [START_TOLERANCE_MILLIS] from every instant the lock's start can name, read as UTC and in [zone] (the system's).
         * Either sign vouches, as for the session files: a claude command whose start reads another time (a zone, a
         * clock that drifted), or a process started then whose command Skein does not know as claude (a wrapper, a
         * script). What the system does not tell, or a start that cannot be read, proves nothing. Blocking: asks [probe].
         */
        fun of(locked: Boolean, reason: String?, probe: ProcessProbe, zone: ZoneId): WorktreeLock {
            if (!locked) return None
            val lock = ClaudeWorktreeLock.parse(reason) ?: return Foreign
            if (!probe.isAlive(lock.pid)) return Stale(lock)
            val command = probe.command(lock.pid)
            if (command.isEmpty() || SessionDirectoryWatcher.isClaude(command)) return Live(lock.pid)
            val started = probe.startInstant(lock.pid) ?: return Live(lock.pid)
            val written = startsIn(lock.start, zone).ifEmpty { return Live(lock.pid) }
            val sameStart = written.any { abs(it.toEpochMilli() - started.toEpochMilli()) <= START_TOLERANCE_MILLIS }
            return if (sameStart) Live(lock.pid) else Stale(lock)
        }

        /** The instants the lock's [start] can name, read as UTC, as Claude Code was seen to write it, and in [zone]. */
        private fun startsIn(start: String, zone: ZoneId): List<Instant> =
            (ClaudeWorktreeLock.startsOf(start, ZoneOffset.UTC) + ClaudeWorktreeLock.startsOf(start, zone)).distinct()
    }
}
