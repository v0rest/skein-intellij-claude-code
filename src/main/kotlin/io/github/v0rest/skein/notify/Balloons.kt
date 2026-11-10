package io.github.v0rest.skein.notify

/**
 * The balloon each thread has up, at most one: a new one replaces the thread's old one, a dismissed or expired one
 * is forgotten. [expire] takes a balloon down; it is called outside the lock, so it may call back into [expired].
 * Safe from any thread.
 */
class Balloons<T : Any>(private val expire: (T) -> Unit) {
    private val current = HashMap<String, T>()

    /** Registers [balloon] for the thread and takes down the one it had. */
    fun replace(threadId: String, balloon: T) {
        val old = synchronized(current) { current.put(threadId, balloon) }
        if (old != null && old !== balloon) expire(old)
    }

    /** Takes down and forgets the thread's balloon, if it has one. */
    fun dismiss(threadId: String) {
        val old = synchronized(current) { current.remove(threadId) } ?: return
        expire(old)
    }

    /** [balloon] went away on its own (the user acted on it, or it was replaced): forget it unless the thread has a newer one. */
    fun expired(threadId: String, balloon: T) {
        synchronized(current) { if (current[threadId] === balloon) current.remove(threadId) }
    }

    fun current(threadId: String): T? = synchronized(current) { current[threadId] }
}
