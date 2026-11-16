package io.github.v0rest.skein.sound

/** How a file is played on this system. Its calls run on pooled threads, never on the EDT. */
interface SoundOutput {
    /**
     * Starts the file at [path], as the disk last told of it in [facts], and gives what [stop] takes to cut it short, or
     * null for a sound that cannot be cut. Throws when the file cannot play, and the caller beeps then. [ended] hears of a
     * sound that stopped by itself.
     */
    fun start(path: String, facts: FileFacts, ended: (Any) -> Unit): Any?

    fun stop(sound: Any)

    /** The IDE is closing. */
    fun dispose()
}

/** Which way each system plays a file. */
object SoundOutputs {
    enum class Kind {
        /**
         * macOS plays through its own alert sound service, AudioToolbox. Java Sound there opens the input side as well,
         * and macOS asks for the microphone, which Skein never needs.
         */
        SYSTEM_SOUND_SERVICE,

        /** Windows and Linux play through Java Sound. */
        JAVA_SOUND,
    }

    fun kind(os: SoundOs): Kind = if (os == SoundOs.MAC) Kind.SYSTEM_SOUND_SERVICE else Kind.JAVA_SOUND
}

/** The system sound service would not take a file - the caller beeps and logs it once. */
class SoundFailure(message: String) : Exception(message)

/**
 * The native calls the system sound service takes, behind an interface so their order runs on fakes in the tests. [U]
 * is a file URL.
 */
interface SystemSoundCalls<U : Any> {
    /** What AudioServicesCreateSystemSoundID answered - its OSStatus, and the sound's ID when that is 0. */
    data class Created(val status: Int, val id: Int)

    /** The file URL of [path], null when CoreFoundation gives none. */
    fun url(path: String): U?

    fun create(url: U): Created

    fun play(id: Int)

    fun dispose(id: Int)

    fun release(url: U)
}

/**
 * Plays files through the system sound service. It keeps each file's sound ID, since disposing one right after it plays
 * would cut the sound - by the file's path, size and last change, so a file overwritten under the same name gets a new
 * sound and its old ID goes - and at most [MAX_IDS] of them, the one played longest ago going first. A URL lives only as
 * long as the call that needs it. Not thread-safe, the macOS output locks it.
 */
class SystemSounds<U : Any>(private val calls: SystemSoundCalls<U>) {
    private class Kept(val id: Int, val facts: FileFacts)

    private val ids = LinkedHashMap<String, Kept>(16, 0.75f, true)

    /** Throws [SoundFailure] for a file the service will not take, and whatever a native call throws. */
    fun play(path: String, facts: FileFacts) {
        val kept = ids[path]
        val id = if (kept != null && kept.facts == facts) {
            kept.id
        } else {
            if (kept != null) {
                ids.remove(path)
                calls.dispose(kept.id)
            }
            create(path, facts)
        }
        calls.play(id)
    }

    private fun create(path: String, facts: FileFacts): Int {
        val url = calls.url(path) ?: throw SoundFailure("CoreFoundation gave no URL for the file")
        val created = try {
            calls.create(url)
        } finally {
            calls.release(url)
        }
        // a file it refuses is asked about again next time, it may have been fixed
        if (created.status != NO_ERR) throw SoundFailure("AudioServicesCreateSystemSoundID returned ${created.status}")
        ids[path] = Kept(created.id, facts)
        while (ids.size > MAX_IDS) {
            val eldest = ids.entries.first()
            ids.remove(eldest.key)
            calls.dispose(eldest.value.id)
        }
        return created.id
    }

    fun disposeAll() {
        val all = ids.values.map { it.id }
        ids.clear()
        all.forEach(calls::dispose)
    }

    companion object {
        /** More sounds than the list offers, so only someone trying file after file ever sees one go. */
        const val MAX_IDS = 32

        private const val NO_ERR = 0
    }
}
