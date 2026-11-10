package io.github.v0rest.skein.process

import com.intellij.openapi.diagnostic.logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import kotlinx.coroutines.CancellationException

/** Sends SIGHUP, what a terminal sends the processes in it when it closes, to one process. */
fun interface HangUp {
    /** True when the signal went out; false for a pid Skein never signals ([HangUps.isSignallable]) or a failure. */
    fun hangUp(pid: Long): Boolean
}

object HangUps {
    /** SIGHUP on macOS and Linux. */
    const val SIGHUP = 1

    /**
     * Whether [pid] may get a signal at all. kill(2) takes 0 as the caller's own process group (the IDE's) and -1 as every
     * process the user owns, 1 is init, and a pid beyond a C int would wrap: none of them is ever signalled.
     */
    fun isSignallable(pid: Long): Boolean = pid > 1 && pid <= Int.MAX_VALUE
}

/**
 * [HangUp] through libc's kill(2), loaded with JNA in the IDE's own process, so Skein starts no process for it. The
 * platform's own helpers for this (`UnixProcessManager.sendSignal`, `NativeAccess.kill`) are `@ApiStatus.Internal`, and
 * JNA's `LibC` has no `kill`. On a system other than macOS or Linux it sends nothing.
 */
class LibcHangUp : HangUp {
    interface LibC : Library {
        fun kill(pid: Int, signal: Int): Int
    }

    @Volatile
    private var libc: LibC? = null

    @Volatile
    private var broken = false

    override fun hangUp(pid: Long): Boolean {
        if (!HangUps.isSignallable(pid) || broken || !(Platform.isMac() || Platform.isLinux())) return false
        return try {
            val lib = libc ?: Native.load(Platform.C_LIBRARY_NAME, LibC::class.java).also { libc = it }
            val result = lib.kill(pid.toInt(), HangUps.SIGHUP)
            // ESRCH (the process ended meanwhile) is the usual reason, and harmless
            if (result != 0) LOG.info("Skein could not hang up process $pid: errno ${Native.getLastError()}")
            result == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            broken = true
            LOG.warn("Skein cannot send signals on this system, so a closed thread's claude may be left running", e)
            false
        }
    }

    private companion object {
        private val LOG = logger<LibcHangUp>()
    }
}
