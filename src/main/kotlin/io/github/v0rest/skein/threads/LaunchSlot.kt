package io.github.v0rest.skein.threads

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * One `claude` launch at a time across every project window, so a status file that appears can only belong to the
 * thread that typed last, and one owner per tab anchor, so no window adopts another window's tab. A holder that never
 * reported back is let go after [timeoutMillis]. All methods are atomic.
 */
class LaunchSlot(private val timeoutMillis: Long = 30_000) {
    private var holder: String? = null
    private var holderOwner: Any? = null
    private var heldSince: Long = 0
    private val anchors = HashMap<Long, Any>()

    @Synchronized
    fun tryAcquire(threadId: String, now: Long, owner: Any? = null): Boolean {
        if (holder == threadId) return true
        if (holder != null && now - heldSince <= timeoutMillis) return false
        holder = threadId
        holderOwner = owner
        heldSince = now
        return true
    }

    @Synchronized
    fun release(threadId: String) {
        if (holder == threadId) {
            holder = null
            holderOwner = null
        }
    }

    /** The thread whose launch is in flight, or null when none is (or the holder expired). */
    @Synchronized
    fun holder(now: Long): String? = holder?.takeIf { now - heldSince <= timeoutMillis }

    /** Claims [anchor] for [owner]; false when another owner already has it. */
    @Synchronized
    fun claimAnchor(owner: Any, anchor: Long): Boolean {
        val current = anchors[anchor]
        if (current != null && current !== owner) return false
        anchors[anchor] = owner
        return true
    }

    @Synchronized
    fun releaseAnchor(owner: Any, anchor: Long) {
        if (anchors[anchor] === owner) anchors.remove(anchor)
    }

    @Synchronized
    fun ownedAnchors(): Set<Long> = anchors.keys.toSet()

    /** Drops everything [owner] held: its anchors and a launch it had in flight. Called when a project closes. */
    @Synchronized
    fun forget(owner: Any) {
        anchors.values.removeAll { it === owner }
        if (holderOwner === owner) {
            holder = null
            holderOwner = null
        }
    }
}

/** The application-wide [LaunchSlot]. */
@Service(Service.Level.APP)
class LaunchSlotService {
    val slot = LaunchSlot()

    companion object {
        fun getInstance(): LaunchSlotService = service()
    }
}
