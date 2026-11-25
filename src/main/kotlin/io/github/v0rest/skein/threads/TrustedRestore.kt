package io.github.v0rest.skein.threads

import java.util.concurrent.atomic.AtomicBoolean

/**
 * When the saved threads come back ([ThreadService.restoreOnce]): at once in a project the user trusts, else only once
 * they trust it, since a project opened without trust may have brought its saved threads in its own files. Whichever
 * look finds the project trusted first restores, and only that one - the trust can be told twice, and the startup, the
 * message bus and the service's tick look from different threads. IDE-free, and safe on any thread.
 */
class TrustedRestore {
    private val requested = AtomicBoolean(false)
    private val ran = AtomicBoolean(false)

    /** The project asks for its saved threads, [trusted] or not; true when they come back now, false while they wait. */
    fun requested(trusted: Boolean): Boolean {
        requested.set(true)
        return look(trusted)
    }

    /**
     * One more look at the trust - after a trust was heard, after each arm of the listener, on each tick while they wait;
     * true once, when they come back now.
     */
    fun look(trusted: Boolean): Boolean = trusted && requested.get() && ran.compareAndSet(false, true)

    /** Whether the saved threads were asked for and still wait for the project's trust. */
    val waiting: Boolean
        get() = requested.get() && !ran.get()

    /**
     * The restore [look] let through did not run after all - the project began to close meanwhile - so the saved threads
     * wait again, and a later look brings them back once a close the user refused left the project open.
     */
    fun didNotRun() {
        ran.set(false)
    }
}
