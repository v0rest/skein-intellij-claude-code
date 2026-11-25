package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** When the saved threads come back: at once in a trusted project, else once the user trusts it, and only once. */
class TrustedRestoreTest {
    @Test
    fun inATrustedProjectTheyComeBackAtOnceAndOnce() {
        val gate = TrustedRestore()
        assertTrue(gate.requested(trusted = true))
        assertFalse(gate.waiting)
        assertFalse(gate.look(trusted = true))
    }

    /** In a project opened without trust they wait, and the first look that finds it trusted brings them back. */
    @Test
    fun inAnUntrustedProjectTheyWaitForTheTrust() {
        val gate = TrustedRestore()
        assertFalse(gate.requested(trusted = false))
        assertTrue(gate.waiting)
        assertFalse(gate.look(trusted = false))
        assertTrue(gate.waiting)
        assertTrue(gate.look(trusted = true))
        assertFalse(gate.waiting)
    }

    /** The trust can be told twice (the Safe Mode dialog's action does), and the restore still runs once. */
    @Test
    fun aSecondTrustRestoresNothing() {
        val gate = TrustedRestore()
        gate.requested(trusted = false)
        assertTrue(gate.look(trusted = true))
        assertFalse(gate.look(trusted = true))
    }

    /** A trust heard before the project asked for its threads brings nothing back - the request itself looks then. */
    @Test
    fun aTrustBeforeTheRequestBringsNothingBack() {
        val gate = TrustedRestore()
        assertFalse(gate.look(trusted = true))
        assertFalse(gate.waiting)
        assertTrue(gate.requested(trusted = true))
    }

    /** The startup and the message bus look at once from two threads, and only one of them restores. */
    @Test
    fun twoLooksAtOnceRestoreOnce() {
        repeat(200) {
            val gate = TrustedRestore()
            gate.requested(trusted = false)
            val pool = Executors.newFixedThreadPool(4)
            val go = CountDownLatch(1)
            val runs = AtomicInteger()
            repeat(4) {
                pool.execute {
                    go.await()
                    if (gate.look(trusted = true)) runs.incrementAndGet()
                }
            }
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, runs.get())
        }
    }

    /**
     * A restore that did not run after all, since the project began to close, waits again: a close the user refused leaves
     * the project open, and a later look brings the threads back, once.
     */
    @Test
    fun aRestoreThatDidNotRunWaitsAgain() {
        val gate = TrustedRestore()
        gate.requested(trusted = false)
        assertTrue(gate.look(trusted = true))
        gate.didNotRun()
        assertTrue(gate.waiting)
        assertTrue(gate.look(trusted = true))
        assertFalse(gate.look(trusted = true))
    }
}
