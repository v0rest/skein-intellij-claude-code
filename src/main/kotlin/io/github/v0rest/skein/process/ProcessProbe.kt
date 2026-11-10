package io.github.v0rest.skein.process

import java.time.Instant

/** The few facts about local processes Skein needs, behind an interface so tests can fake the process tree. */
interface ProcessProbe {
    fun currentPid(): Long
    fun isAlive(pid: Long): Boolean
    fun startInstant(pid: Long): Instant?
    fun parent(pid: Long): Long?

    /** The direct children of [pid], empty when it is gone. */
    fun children(pid: Long): List<Long>

    /** The executable of [pid] followed by its arguments; empty when unknown (another user's process, a race). */
    fun command(pid: Long): List<String>
}

/** [ProcessProbe] over the JDK's [ProcessHandle]; public API, works on macOS and Linux. */
class JdkProcessProbe : ProcessProbe {
    override fun currentPid(): Long = ProcessHandle.current().pid()

    override fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    override fun startInstant(pid: Long): Instant? = ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null)

    override fun parent(pid: Long): Long? = ProcessHandle.of(pid).flatMap { it.parent() }.map { it.pid() }.orElse(null)

    override fun children(pid: Long): List<Long> =
        ProcessHandle.of(pid).map { handle -> handle.children().map { it.pid() }.toList() }.orElse(emptyList())

    override fun command(pid: Long): List<String> {
        val info = ProcessHandle.of(pid).map { it.info() }.orElse(null) ?: return emptyList()
        val executable = info.command().orElse(null) ?: return emptyList()
        return listOf(executable) + info.arguments().orElse(emptyArray())
    }
}

/**
 * One process, told apart from any later process that gets the same pid: the pid and the instant it started, as
 * [ProcessProbe.startInstant] reports it.
 */
data class ProcessId(val pid: Long, val start: Instant)

/**
 * Some processes as they were at one moment: [known] with their start instants, and [startUnknown], the pids whose start
 * the system did not report then. A pid in neither was not there.
 */
data class ProcessList(val known: Set<ProcessId>, val startUnknown: Set<Long>) {
    /** Whether the process with this pid was there; null when its start could not be read then, so nobody can tell. */
    fun had(process: ProcessId): Boolean? = if (process.pid in startUnknown) null else process in known

    /** Whether some process held [pid] then, whatever it was. */
    fun hasPid(pid: Long): Boolean = pid in startUnknown || known.any { it.pid == pid }

    companion object {
        val EMPTY = ProcessList(emptySet(), emptySet())
    }
}
