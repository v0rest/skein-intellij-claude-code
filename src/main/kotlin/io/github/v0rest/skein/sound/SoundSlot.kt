package io.github.v0rest.skein.sound

/**
 * One event's sounds with [SoundGate] applied - whether the next one plays, the last one stopping first, and when it
 * started. Generic over the clip, so it is tested with a fake one and a fake clock. Not thread-safe, the player locks it.
 */
class SoundSlot<C : Any>(private val clock: () -> Long, private val stop: (C) -> Unit) {
    private var playing: C? = null
    private var lastStart: Long? = null

    /**
     * Plays through [start] unless the event is in a burst, and says whether it did. [start] gives the clip now playing,
     * or null for a beep or a file that could not start - a beep starts a burst all the same.
     */
    fun play(preview: Boolean, start: () -> C?): Boolean {
        if (SoundGate.skip(lastStart, clock(), preview)) return false
        // the event's last sound, still playing or not, stops before the next one starts
        playing?.let(stop)
        playing = null
        playing = start()
        // taken once the sound started, so a slow open does not eat into the burst
        lastStart = clock()
        return true
    }

    /** [clip] stopped by itself and was closed, so a later sound does not stop it again. */
    fun ended(clip: C) {
        if (playing === clip) playing = null
    }

    /** The IDE is closing, so whatever plays stops. */
    fun stopAll() {
        playing?.let(stop)
        playing = null
    }
}
