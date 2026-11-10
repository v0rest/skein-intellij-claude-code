package io.github.v0rest.skein.testutil

import io.github.v0rest.skein.process.ProcessProbe
import java.time.Instant

/** An in-memory process tree for tests: pids, their parents and start times. */
class FakeProcessProbe(var current: Long = 1) : ProcessProbe {
    private data class Proc(val parent: Long?, val start: Instant, val command: List<String>)

    private val procs = mutableMapOf<Long, Proc>()

    /** Processes whose start the system does not report, as can happen for one that is going away. */
    val unknownStart = mutableSetOf<Long>()

    /** Listing a process's children throws, as a broken process table would. */
    var childrenFail = false

    fun add(pid: Long, parent: Long?, start: Instant, command: List<String> = emptyList()) {
        procs[pid] = Proc(parent, start, command)
    }

    fun kill(pid: Long) {
        procs.remove(pid)
    }

    override fun currentPid(): Long = current

    override fun isAlive(pid: Long): Boolean = pid in procs

    override fun startInstant(pid: Long): Instant? = if (pid in unknownStart) null else procs[pid]?.start

    override fun parent(pid: Long): Long? = procs[pid]?.parent

    override fun children(pid: Long): List<Long> {
        check(!childrenFail) { "process listing failed" }
        return procs.filterValues { it.parent == pid }.keys.sorted()
    }

    override fun command(pid: Long): List<String> = procs[pid]?.command.orEmpty()
}
