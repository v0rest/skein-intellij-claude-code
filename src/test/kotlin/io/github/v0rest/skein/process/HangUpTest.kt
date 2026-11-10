package io.github.v0rest.skein.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HangUpTest {
    /**
     * kill(2) takes 0 as the caller's own process group and -1 as every process the user owns; 1 is init. None of them,
     * and nothing beyond a C int, ever gets a signal from Skein.
     */
    @Test
    fun onlyAnOrdinaryPidCanBeSignalled() {
        for (pid in listOf(Long.MIN_VALUE, -2L, -1L, 0L, 1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            assertFalse("pid $pid", HangUps.isSignallable(pid))
        }
        for (pid in listOf(2L, 300L, 55555L, Int.MAX_VALUE.toLong())) assertTrue("pid $pid", HangUps.isSignallable(pid))
    }

    @Test
    fun refusesWhatItMustNotSignal() {
        val libc = LibcHangUp()
        assertFalse(libc.hangUp(0))
        assertFalse(libc.hangUp(-1))
        assertFalse(libc.hangUp(1))
    }

    /**
     * The real thing on macOS and Linux: a process started here ends on Skein's SIGHUP, as on a closing terminal's. The
     * child first sets SIGHUP back to its default, since a test run under nohup or a CI runner may hand it down ignored;
     * perl, because a shell cannot reset a signal that was ignored when it started (`trap - HUP` leaves it ignored).
     */
    @Test
    fun aRealProcessEndsOnTheHangUp() {
        val os = System.getProperty("os.name").lowercase()
        assumeTrue(os.contains("mac") || os.contains("linux"))
        val perl = listOf("/usr/bin/perl", "/bin/perl").firstOrNull { java.io.File(it).canExecute() }
        assumeTrue("no perl to start the child with", perl != null)
        val sleeper = ProcessBuilder(perl, "-e", "\$SIG{HUP} = 'DEFAULT'; exec 'sleep', '30'").start()
        try {
            // a signal before perl reset it could still meet the inherited "ignored": wait for the exec into sleep
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!sleeper.info().command().orElse("").endsWith("sleep") && System.nanoTime() < until) Thread.sleep(20)
            assertTrue(LibcHangUp().hangUp(sleeper.pid()))
            assertTrue("sleep did not end on SIGHUP", sleeper.waitFor(5, TimeUnit.SECONDS))
            // a shell reports a process killed by signal n as 128 + n; the JDK reports the same for SIGHUP (1)
            assertEquals(129, sleeper.exitValue())
        } finally {
            sleeper.destroyForcibly()
        }
    }
}
